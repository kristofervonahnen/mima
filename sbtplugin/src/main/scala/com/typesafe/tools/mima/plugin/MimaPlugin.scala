package com.typesafe.tools.mima
package plugin

import sbt.*
import Keys.*
import core.*
import sbt.librarymanagement.{DependencyResolution, ScalaModuleInfo}

/** MiMa's sbt plugin. */
object MimaPlugin extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport extends MimaKeys
  import autoImport.*

  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    mimaPreviousArtifacts := NoPreviousArtifacts,
    mimaExcludeAnnotations := Nil,
    mimaBinaryIssueFilters := Nil,
    mimaFailOnProblem := true,
    mimaFailOnNoPrevious := true,
    mimaReportSignatureProblems := false,
    mimaCheckDirection := "backward",
    mimaUseSbtAssemblyArtifact := false,
    mimaSbtAssemblyArtifactJarName := "",
  )

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    mimaReportBinaryIssues := {
      binaryIssuesIterator.value.foreach { case (moduleId, problems) =>
        SbtMima.reportModuleErrors(
          moduleId,
          problems._1,
          problems._2,
          mimaFailOnProblem.value,
          binaryIssueFilters.value,
          mimaBackwardIssueFilters.value,
          mimaForwardIssueFilters.value,
          streams.value.log,
          name.value,
          if (mimaUseSbtAssemblyArtifact.value) "assembly" else "none", // TODO Would be good to determine classifier via a method.
        )
      }
    },
    mimaDependencyResolution := dependencyResolution.value,
    mimaPreviousClassfiles := {
      artifactsToClassfiles.value.toClassfiles(mimaPreviousArtifacts.value)
    },
    mimaCurrentClassfiles := {
      mimaUseSbtAssemblyArtifact.value match {
        case true => {
          sbtAssemblyRunner.value.run() // TODO Need to monitor and handle sbtAssemblyRunner.value.run failures.
          currentSbtAssemblyArtifactsToClassfiles.value.toClassfiles()
        }
        case false => (Compile / classDirectory).value
      }
    },
    mimaFindBinaryIssues := binaryIssuesIterator.value.toMap,
    mimaFindBinaryIssues / fullClasspath := (Compile / fullClasspath).value,
    mimaBackwardIssueFilters := SbtMima.issueFiltersFromFiles(mimaFiltersDirectory.value, "\\.(?:backward[s]?|both)\\.excludes".r, streams.value),
    mimaForwardIssueFilters := SbtMima.issueFiltersFromFiles(mimaFiltersDirectory.value, "\\.(?:forward[s]?|both)\\.excludes".r, streams.value),
    mimaFiltersDirectory := (Compile / sourceDirectory).value / "mima-filters",
  )

  @deprecated("Switch to enablePlugins(MimaPlugin)", "0.7.0")
  def mimaDefaultSettings: Seq[Setting[_]] = globalSettings ++ buildSettings ++ projectSettings

  trait CurrentSbtAssemblyArtifactsToClassfiles {
    def toClassfiles(): File
  }

  def currentSbtAssemblyArtifactsToClassfiles: Def.Initialize[Task[CurrentSbtAssemblyArtifactsToClassfiles]] = Def.task {
    () => {
      val assemblyJarName = mimaSbtAssemblyArtifactJarName.value match {
        case "" => s"${artifact.value.name}-assembly-${version.value}.jar"
        case _  => mimaSbtAssemblyArtifactJarName.value
      }

      // TODO There must be a better way to get this file path...
      (Compile / classDirectory).value / s"../$assemblyJarName"
    }
  }

  trait ArtifactsToClassfiles {
    def toClassfiles(previousArtifacts: Set[ModuleID]): Map[ModuleID, File]
  }

  trait BinaryIssuesFinder {
    def runMima(prevClassFiles: Map[ModuleID, File], checkDirection: String)
    : Iterator[(ModuleID, (List[Problem], List[Problem]))]
  }

  val artifactsToClassfiles: Def.Initialize[Task[ArtifactsToClassfiles]] = Def.task {
    val depRes = mimaDependencyResolution.value
    val taskStreams = streams.value
    val smi = scalaModuleInfo.value
    val classifier = if (mimaUseSbtAssemblyArtifact.value) Some("assembly") else None
    previousArtifacts => previousArtifacts match {
      case _: NoPreviousArtifacts.type => NoPreviousClassfiles
      case previousArtifacts =>
        previousArtifacts.iterator.map { m =>
          val moduleId = CrossVersion(m, smi) match {
            case Some(f) => m.withName(f(m.name)).withCrossVersion(CrossVersion.disabled)
            case None => m
          }
          moduleId -> SbtMima.getPreviousArtifact(moduleId, depRes, taskStreams, classifier)
        }.toMap
    }
  }

  val binaryIssuesFinder: Def.Initialize[Task[BinaryIssuesFinder]] = Def.task {
    val log = streams.value.log
    val currClassfiles = mimaCurrentClassfiles.value
    val cp = (mimaFindBinaryIssues / fullClasspath).value
    val sv = scalaVersion.value
    val excludeAnnots = mimaExcludeAnnotations.value.toList
    val failOnNoPrevious = mimaFailOnNoPrevious.value
    val projName = name.value

    (prevClassfiles, checkDirection) => {
      if (prevClassfiles eq NoPreviousClassfiles) {
        val msg = "mimaPreviousArtifacts not set, not analyzing binary compatibility"
        if (failOnNoPrevious) sys.error(msg) else log.info(s"$projName: $msg")
      } else if (prevClassfiles.isEmpty) {
        log.info(s"$projName: mimaPreviousArtifacts is empty, not analyzing binary compatibility.")
      }

      prevClassfiles.iterator.map { case (moduleId, prevClassfiles) =>
        moduleId -> SbtMima.runMima(prevClassfiles, currClassfiles, cp, checkDirection, sv, log, excludeAnnots)
      }
    }
  }

  private val binaryIssueFilters = Def.task {
    val noSigs = ProblemFilters.exclude[IncompatibleSignatureProblem]("*")
    mimaBinaryIssueFilters.value ++ (if (mimaReportSignatureProblems.value) Nil else Seq(noSigs))
  }

  // Allows reuse between mimaFindBinaryIssues and mimaReportBinaryIssues
  // without blowing up the Akka build's heap
  private val binaryIssuesIterator = Def.task {
    binaryIssuesFinder.value.runMima(mimaPreviousClassfiles.value, mimaCheckDirection.value)
  }

  // Used to differentiate unset mimaPreviousArtifacts from empty mimaPreviousArtifacts
  private object NoPreviousArtifacts extends EmptySet[ModuleID]
  private object NoPreviousClassfiles extends EmptyMap[ModuleID, File]

  // Used to differentiate unset NoCurrentArtifactsOverride from default behavior
  private object NoCurrentArtifactsOverride extends EmptySet[ModuleID]

  private sealed class EmptySet[A] extends Set[A] {
    def iterator          = Iterator.empty
    def contains(elem: A) = false
    def + (elem: A)       = Set(elem)
    def - (elem: A)       = this

    override def size                  = 0
    override def foreach[U](f: A => U) = ()
    override def toSet[B >: A]: Set[B] = this.asInstanceOf[Set[B]]
  }

  private sealed class EmptyMap[K, V] extends Map[K, V] {
    def get(key: K)              = None
    def iterator                 = Iterator.empty
    def + [V1 >: V](kv: (K, V1)) = updated(kv._1, kv._2)
    def - (key: K)               = this

    override def size                                       = 0
    override def contains(key: K)                           = false
    override def getOrElse[V1 >: V](key: K, default: => V1) = default
    override def updated[V1 >: V](key: K, value: V1)        = Map(key -> value)

    override def apply(key: K) = throw new NoSuchElementException(s"key not found: $key")
  }

  trait SbtAssemblyRunner {
    def run(): Unit
  }

  val sbtAssemblyRunner: Def.Initialize[Task[SbtAssemblyRunner]] = Def.task {
    val currentState = state.value

    () => {
      val cmd = s"${artifact.value.name}/assembly"
      val result = Command.process(cmd, currentState) // TODO Determine what to do with the result.
    }
  }

  /**
   * Convert the given command string to a release step action, preserving and      invoking remaining commands
   * Note: This was copied from https://github.com/sbt/sbt-release/blob/663cfd426361484228a21a1244b2e6b0f7656bdf/src/main/scala/ReleasePlugin.scala#L99-L115
   */
  def runCommandAndRemaining(command: String): State => State = { st: State =>
    import sbt.complete.Parser
    @annotation.tailrec
    def runCommand(command: String, state: State): State = {
      val nextState = Parser.parse(command, state.combinedParser) match {
        case Right(cmd) => cmd()
        case Left(msg) => throw sys.error(s"Invalid programmatic input:\n$msg")
      }
      nextState.remainingCommands.toList match {
        case Nil => nextState
        case head :: tail => runCommand(head.commandLine, nextState.copy(remainingCommands = tail))
      }
    }
    runCommand(command, st.copy(remainingCommands = Nil)).copy(remainingCommands = st.remainingCommands)
  }
}
