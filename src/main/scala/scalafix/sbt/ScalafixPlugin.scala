package scalafix.sbt

import java.nio.file.Path
import sbt.Def
import sbt.Keys._
import sbt._
import sbt.internal.sbtscalafix.JLineAccess
import sbt.plugins.JvmPlugin
import scala.collection.JavaConverters._
import scala.util.control.NonFatal
import scalafix.interfaces._
import scalafix.interfaces.{Scalafix => ScalafixAPI}
import scalafix.internal.sbt.ScalafixCompletions
import scalafix.internal.sbt.ScalafixInterface
import scalafix.internal.sbt.ShellArgs

object ScalafixPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin

  object autoImport {
    val Scalafix = Tags.Tag("scalafix")

    val scalafix: InputKey[Unit] =
      inputKey[Unit](
        "Run scalafix rule in this project and configuration. " +
          "For example: scalafix RemoveUnusedImports. " +
          "To run on test sources use test:scalafix."
      )
    val scalafixDependencies: SettingKey[Seq[ModuleID]] =
      settingKey[Seq[ModuleID]](
        "Optional list of custom rules to install from Maven Central. " +
          "This setting is read from the global scope so it only needs to be defined once in the build."
      )
    val scalafixConfig: SettingKey[Option[File]] =
      settingKey[Option[File]](
        "Optional location to .scalafix.conf file to specify which scalafix rules should run. " +
          "Defaults to the build base directory if a .scalafix.conf file exists."
      )
    val scalafixSemanticdb: ModuleID =
      scalafixSemanticdb(BuildInfo.scalametaVersion)
    def scalafixSemanticdb(scalametaVersion: String): ModuleID =
      "org.scalameta" % "semanticdb-scalac" % scalametaVersion cross CrossVersion.full

    def scalafixConfigSettings(config: Configuration): Seq[Def.Setting[_]] =
      Seq(
        scalafix := scalafixInputTask(config).evaluated
      )

    @deprecated("This setting is no longer used", "0.6.0")
    val scalafixSourceroot: SettingKey[File] =
      settingKey[File]("Unused")
    @deprecated("Use scalacOptions += -Yrangepos instead", "0.6.0")
    def scalafixScalacOptions: Def.Initialize[Seq[String]] =
      Def.setting(Nil)
    @deprecated("Use addCompilerPlugin(semanticdb-scalac) instead", "0.6.0")
    def scalafixLibraryDependencies: Def.Initialize[List[ModuleID]] =
      Def.setting(Nil)

    @deprecated(
      "Use addCompilerPlugin(scalafixSemanticdb) and scalacOptions += \"-Yrangepos\" instead",
      "0.6.0"
    )
    def scalafixSettings: Seq[Def.Setting[_]] =
      Nil
  }

  import autoImport._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(Compile, Test).flatMap(c => inConfig(c)(scalafixConfigSettings(c)))

  override def globalSettings: Seq[Def.Setting[_]] = Seq(
    initialize := {
      val _ = initialize.value
      // Ideally, we would not resort to storing mutable state in `initialize`.
      // The optimal solution would be to run `scalafixDependencies.value`
      // inside `scalafixInputTask`.
      // However, we can't do that due to an sbt bug:
      //   https://github.com/sbt/sbt/issues/3572#issuecomment-417582703
      workingDirectory = baseDirectory.in(ThisBuild).value.toPath
      scalafixInterface = ScalafixInterface.fromToolClasspath(
        scalafixDependencies = scalafixDependencies.in(ThisBuild).value
      )
    },
    scalafixConfig := None, // let scalafix-cli try to infer $CWD/.scalafix.conf
    scalafixDependencies := Nil,
    commands += ScalafixEnable.command
  )

  private var workingDirectory = file("").getAbsoluteFile.toPath
  private var scalafixInterface: () => ScalafixInterface =
    () => throw new UninitializedError
  private def scalafixAPI(): ScalafixAPI = scalafixInterface().api
  private def scalafixArgs(): ScalafixMainArgs = scalafixInterface().args

  private def scalafixInputTask(
      config: Configuration
  ): Def.Initialize[InputTask[Unit]] =
    Def.inputTaskDyn {
      val args = new ScalafixCompletions(
        workingDirectory = () => workingDirectory,
        loadedRules = () => scalafixArgs().availableRules().asScala,
        terminalWidth = Some(JLineAccess.terminalWidth)
      ).parser.parsed
      if (args.rules.isEmpty && args.extra == List("--help")) scalafixHelp
      else {
        val mainArgs = scalafixArgs()
          .withRules(args.rules.map(_.name).asJava)
          .safeWithArgs(args.extra)
        val rulesThatWillRun = mainArgs.safeRulesThatWillRun()
        val isSemantic = rulesThatWillRun.exists(_.kind().isSemantic)
        if (isSemantic) {
          val names = rulesThatWillRun.map(_.name())
          scalafixSemantic(names, mainArgs, args, config)
        } else {
          scalafixSyntactic(mainArgs, args, config)
        }
      }
    }
  private def scalafixHelp: Def.Initialize[Task[Unit]] =
    Def.task {
      scalafixArgs().withArgs(List("--help").asJava).run()
      ()
    }

  private def scalafixSyntactic(
      mainArgs: ScalafixMainArgs,
      shellArgs: ShellArgs,
      config: Configuration
  ): Def.Initialize[Task[Unit]] = Def.task {
    runArgs(
      shellArgs,
      filesToFix(shellArgs, config).value,
      mainArgs,
      streams.value.log,
      scalafixConfig.value
    )
  }

  private def scalafixSemantic(
      ruleNames: Seq[String],
      mainArgs: ScalafixMainArgs,
      shellArgs: ShellArgs,
      config: Configuration
  ): Def.Initialize[Task[Unit]] = Def.taskDyn {
    val isSemanticdb =
      libraryDependencies.value.exists(_.name.startsWith("semanticdb-scalac"))
    val files = filesToFix(shellArgs, config).value
    // FIXME: Validate scalacOptions here https://github.com/scalacenter/scalafix/issues/857
    if (isSemanticdb && files.nonEmpty) {
      Def.task {
        val args = mainArgs
          .withScalaVersion(scalaVersion.value)
          .withScalacOptions(scalacOptions.value.asJava)
          .withClasspath(fullClasspath.value.map(_.data.toPath).asJava)
        runArgs(
          shellArgs,
          files,
          args,
          streams.value.log,
          scalafixConfig.value
        )
      }
    } else {
      Def.task {
        val names = ruleNames.mkString(", ")
        throw new MissingSemanticdb(names)
      }
    }
  }

  private def runArgs(
      shellArgs: ShellArgs,
      paths: Seq[Path],
      mainArgs: ScalafixMainArgs,
      logger: Logger,
      config: Option[File]
  ): Unit = {
    var finalArgs = mainArgs

    if (paths.nonEmpty) {
      finalArgs = finalArgs.withPaths(paths.asJava)
    }

    config match {
      case Some(file) =>
        finalArgs = finalArgs.withConfig(file.toPath)
      case None =>
    }

    if (paths.nonEmpty || shellArgs.explicitlyListsFiles) {

      if (paths.lengthCompare(1) > 0) {
        logger.info(
          s"Running scalafix on ${paths.size} Scala sources"
        )
      }

      val errors = finalArgs.run()
      if (errors.nonEmpty) {
        throw new ScalafixFailed(errors.toList)
      }
    } else {
      () // do nothing
    }
  }

  private def isScalaFile(file: File): Boolean = {
    val path = file.getPath
    path.endsWith(".scala") ||
    path.endsWith(".sbt")
  }
  private def filesToFix(
      shellArgs: ShellArgs,
      config: Configuration
  ): Def.Initialize[Task[Seq[Path]]] =
    Def.taskDyn {
      // Dynamic task to avoid redundantly computing `unmanagedSources.value`
      if (shellArgs.explicitlyListsFiles) {
        Def.task {
          Nil
        }
      } else {
        Def.task {
          for {
            source <- unmanagedSources.in(config).in(scalafix).value
            if source.exists()
            if isScalaFile(source)
          } yield source.toPath
        }
      }
    }

  implicit class XtensionArgs(mainArgs: ScalafixMainArgs) {
    def safeRulesThatWillRun(): Seq[ScalafixRule] = {
      try mainArgs.rulesThatWillRun().asScala
      catch {
        case NonFatal(e) =>
          throw new InvalidArgument(e.getMessage)
      }
    }
    def safeWithArgs(args: Seq[String]): ScalafixMainArgs = {
      try mainArgs.withArgs(args.asJava)
      catch {
        case NonFatal(e) =>
          throw new InvalidArgument(e.getMessage)
      }
    }
  }
}
