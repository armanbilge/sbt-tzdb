package io.gitub.sbt.tzdb

import java.io.{ File => JFile }
import sbt._
import sbt.util.Logger
import Keys._
import cats.effect

object TzdbPlugin extends AutoPlugin {
  sealed trait TZDBVersion {
    val id: String
    val path: String
  }
  case object LatestVersion extends TZDBVersion {
    val id: String   = "latest"
    val path: String = "tzdata-latest"
  }
  final case class Version(version: String) extends TZDBVersion {
    val id: String   = version
    val path: String = s"releases/tzdata$version"
  }

  object autoImport {

    /*
     * Settings
     */
    val zonesFilter                   = settingKey[String => Boolean]("Filter for zones")
    val dbVersion                     = settingKey[TZDBVersion]("Version of the tzdb")
    val tzdbCodeGen                   =
      taskKey[Seq[JFile]]("Generate scala.js compatible database of tzdb data")
    val includeTTBP: TaskKey[Boolean] =
      taskKey[Boolean]("Include also a provider for threeten bp")
    val jsOptimized: TaskKey[Boolean] =
      taskKey[Boolean]("Generates a version with smaller size but only usable on scala.js")
  }

  import autoImport._
  override def trigger            = noTrigger
  override lazy val buildSettings = Seq(
    zonesFilter := { case _ => true },
    dbVersion := LatestVersion,
    includeTTBP := false,
    jsOptimized := true
  )
  override val projectSettings    =
    Seq(
      sourceGenerators in Compile += Def.task {
        tzdbCodeGen.value
      },
      tzdbCodeGen :=
        tzdbCodeGenImpl(
          sourceManaged = (sourceManaged in Compile).value,
          resourcesManaged = (resourceManaged in Compile).value,
          zonesFilter = zonesFilter.value,
          dbVersion = dbVersion.value,
          includeTTBP = includeTTBP.value,
          jsOptimized = jsOptimized.value,
          log = streams.value.log
        )
    )

  def tzdbCodeGenImpl(
    sourceManaged:    JFile,
    resourcesManaged: JFile,
    zonesFilter:      String => Boolean,
    dbVersion:        TZDBVersion,
    includeTTBP:      Boolean,
    jsOptimized:      Boolean,
    log:              Logger
  ): Seq[JFile] = {

    import cats._
    import cats.syntax.all._

    val tzdbData: JFile = resourcesManaged / "tzdb"
    val sub             = if (jsOptimized) "js" else "jvm"
    val ttbp            = IOTasks.copyProvider(sourceManaged,
                                    sub,
                                    "TzdbZoneRulesProvider.scala",
                                    "org.threeten.bp.zone",
                                    false
    )
    val jt              =
      IOTasks.copyProvider(sourceManaged,
                           sub,
                           "TzdbZoneRulesProvider.scala",
                           "java.time.zone",
                           true
      )
    val providerCopy    = if (includeTTBP) List(ttbp, jt) else List(jt)
    (for {
      _ <- IOTasks.downloadTZDB(log, resourcesManaged, dbVersion)
      // Use it to detect if files have been already generated
      p <-
        IOTasks.providerFile(sourceManaged / sub, "TzdbZoneRulesProvider.scala", "java.time.zone")
      e <- effect.IO(p.exists)
      j <- if (e) effect.IO(List(p)) else providerCopy.sequence
      f <- if (e) IOTasks.tzDataSources(sourceManaged, includeTTBP).map(_.map(_._3))
           else
             IOTasks.generateTZDataSources(sourceManaged,
                                           tzdbData,
                                           log,
                                           includeTTBP,
                                           jsOptimized,
                                           zonesFilter
             )
    } yield (j ::: f).toSeq).unsafeRunSync
  }
}
