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
    mimaCurrentArtifactsOverride := NoCurrentArtifactsOverride,
    mimaArtifactsClassifier := "",
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
          mimaArtifactsClassifier.value,
        )
      }
    },
    mimaDependencyResolution := dependencyResolution.value,
    mimaPreviousClassfiles := {
      artifactsToClassfiles.value.toClassfiles(mimaPreviousArtifacts.value, mimaArtifactsClassifier.value)
    },
    mimaCurrentClassfiles := {
      currentArtifactsToClassfiles.value.toClassfiles(mimaCurrentArtifactsOverride.value, mimaArtifactsClassifier.value)
    },
    mimaFindBinaryIssues := binaryIssuesIterator.value.toMap,
    mimaFindBinaryIssues / fullClasspath := (Compile / fullClasspath).value,
    mimaBackwardIssueFilters := SbtMima.issueFiltersFromFiles(mimaFiltersDirectory.value, "\\.(?:backward[s]?|both)\\.excludes".r, streams.value),
    mimaForwardIssueFilters := SbtMima.issueFiltersFromFiles(mimaFiltersDirectory.value, "\\.(?:forward[s]?|both)\\.excludes".r, streams.value),
    mimaFiltersDirectory := (Compile / sourceDirectory).value / "mima-filters",
  )

  @deprecated("Switch to enablePlugins(MimaPlugin)", "0.7.0")
  def mimaDefaultSettings: Seq[Setting[_]] = globalSettings ++ buildSettings ++ projectSettings

  trait CurrentArtifactsOverrideToClassfiles {
    def toClassfiles(currentArtifactsOverride: Set[ModuleID], classifier: String): File
  }

  def currentArtifactsToClassfiles: Def.Initialize[Task[CurrentArtifactsOverrideToClassfiles]] = Def.task {
    val depRes = mimaDependencyResolution.value
    val taskStreams = streams.value
    val smi = scalaModuleInfo.value

    (currentArtifactsOverride, classifier) => currentArtifactsOverride match {
      case NoCurrentArtifactsOverride => (Compile / classDirectory).value
      // TODO Is there a better way to get a single artifact for the override?
      case _                          => constructArtifactsMap(currentArtifactsOverride, classifier, depRes, taskStreams, smi).head._2
    }
  }

  def constructArtifactsMap(artifacts: Set[ModuleID], classifier: String, depRes: DependencyResolution, taskStreams: Keys.TaskStreams, smi: Option[ScalaModuleInfo]): Map[ModuleID, File] = {
    val classifierOpt: Option[String] = if (classifier.isEmpty) None else Some(classifier)

    artifacts.iterator.map { m =>
      val moduleId = CrossVersion(m, smi) match {
        case Some(f) => classifierOpt match {
          case None  => m.withName(f(m.name)).withCrossVersion(CrossVersion.disabled)
          case Some(c) => m.withName(f(m.name)).classifier(c).withCrossVersion(CrossVersion.disabled)
        }
        case None => m
      }
      moduleId -> SbtMima.getPreviousArtifact(moduleId, depRes, taskStreams, classifierOpt)
    }.toMap
  }

  trait ArtifactsToClassfiles {
    def toClassfiles(previousArtifacts: Set[ModuleID], classifier: String): Map[ModuleID, File]
  }

  trait BinaryIssuesFinder {
    def runMima(prevClassFiles: Map[ModuleID, File], checkDirection: String)
    : Iterator[(ModuleID, (List[Problem], List[Problem]))]
  }

  val artifactsToClassfiles: Def.Initialize[Task[ArtifactsToClassfiles]] = Def.task {
    val depRes = mimaDependencyResolution.value
    val taskStreams = streams.value
    val smi = scalaModuleInfo.value

    (previousArtifacts, classifier) => previousArtifacts match {
      case _: NoPreviousArtifacts.type => NoPreviousClassfiles
      case previousArtifacts => constructArtifactsMap(previousArtifacts, classifier, depRes, taskStreams, smi)
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
}
