package io.aklivity.zilla.manager.internal

import arrow.core.Either
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.aklivity.zilla.manager.internal.commands.install.MyZpmInstall
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmCacheKt
import io.aklivity.zilla.manager.internal.commands.install.impl.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import kotlin.io.path.exists

class ZillaManager : CliktCommand(name = "zpm") {
    private val logger = LoggerFactory.getLogger(ZillaManager::class.java)
    private val launcherDir: String by option(
        "--launcher-directory",
        help = "launcher directory"
    ).default("zilla")

    override fun run() {
        logger.info("Zilla Package Manager v0.9.MikeVersion")
        println("I have the launcher directoey")
        println("The launcher directory is $launcherDir")
        currentContext.obj = Paths.get(launcherDir) // Store as Path
    }
}

class WrapCommand : CliktCommand(name = "wrap") {
    private val version: String by option("--version", help = "Zilla Manager version")
        .default("develop-SNAPSHOT")
    private val logger = LoggerFactory.getLogger(WrapCommand::class.java)

    override fun run() {
        logger.info("Wrapping zpm command for version $version")

        val launcherDir = currentContext.findRoot().obj as? Path ?: Paths.get("")
        val wrapperScriptPath = launcherDir.resolve("zpmw")
        val wrapperJarName = "manager-$version.jar"

        val homeString = "\$HOME"+"/.m2/repository/io/aklivity/zilla/manager/$version/$wrapperJarName"

        // Build the absolute path in ~/.m2 for the manager jar
//        val localJarPath = Paths.get("${'$'}wrappedPath")
//            .resolve(".m2/repository/io/aklivity/zilla/manager")
//            .resolve(version)
//            .resolve(wrapperJarName)
//            .toAbsolutePath()
//        val localJarPathStr = localJarPath.toString()
        val localJarPathStr = homeString

        val wrapperContent = """
            |#!/bin/bash
            |version="$version"
            |localPath="$localJarPathStr"
            |wrappedPath=".zpm/wrapper/$wrapperJarName"
            |
            |if [ ! -r "${'$'}wrappedPath" ]; then
            |  mkdir -p "$(dirname "${'$'}wrappedPath")"
            |  if [ -r "${'$'}localPath" ]; then
            |    echo "${'$'}wrappedPath not found, copying from ${'$'}localPath"
            |    cp "${'$'}localPath" "${'$'}wrappedPath"
            |  else
            |    echo "Error: ${'$'}localPath not found. Please run 'mvn install' for manager first."
            |    exit 1
            |  fi
            |fi
            |
            |exec java -jar "${'$'}wrappedPath" "${'$'}@"
        """.trimMargin()

        try {
            // Ensure the launcher dir exists (so the script goes where you expect)
            Files.createDirectories(launcherDir)

            // Write the script
            Files.writeString(wrapperScriptPath, wrapperContent)

            // Make it executable (POSIX, with fallback)
            try {
                Files.setPosixFilePermissions(
                    wrapperScriptPath,
                    PosixFilePermissions.fromString("rwxr-xr-x")
                )
            } catch (ex: UnsupportedOperationException) {
                // e.g. on Windows hosts — fallback
                wrapperScriptPath.toFile().setExecutable(true, /* ownerOnly = */ false)
            }

            logger.info("Created zpmw script at: $wrapperScriptPath")
            echo("Created zpmw script: $wrapperScriptPath")
        } catch (e: Exception) {
            logger.error("Failed to create zpmw: ${e.message}", e)
            throw RuntimeException("Failed to wrap zpm command", e)
        }
    }
}


class InstallCommand : CliktCommand(name = "install") {
    private val templatePath: String by option("--template", "-t", help = "Path to zpm.json template").default("zpm.json")
    private val installDir: String by option("--install-dir", "-d", help = "Installation directory").default(".zpm")
    private val debug: Boolean by option("--debug", help = "Enable debug logging").flag(default = false)
    private val logger = LoggerFactory.getLogger(InstallCommand::class.java)

    override fun run() {
        val template = Paths.get(templatePath)
        val install = Paths.get(installDir)
        val launcherDir = currentContext.findRoot().obj as? Path ?: Paths.get("")
        logger.info("Installing from template $template to $install with launcher directory $launcherDir")
        echo("Installing from $template to $install with launcher directory $launcherDir")

        if (!template.exists()) {
            logger.error("Template file not found: $template")
            echo("Error: Template file not found: $template")
            throw RuntimeException("Template file not found: $template")
        }

        val feedback: (String) -> Unit = { message ->
            if (debug) {
                logger.info(message)
                echo(message)
            } else {
                logger.debug(message)
            }
        }

        val installer = MyZpmInstall(
            cache = ZpmCacheKt(emptyList(), install),
            installDir = install,
            jarCopier = JarCopier(dryRun = false, feedback = feedback),
            manifestMerger = ManifestMerger(dryRun = false, feedback = feedback),
            moduleInfoGenerator = ModuleInfoGenerator(dryRun = false, feedback = feedback),
            imageLinker = DefaultImageLinker(dryRun = false, feedback = feedback),
            launcherWriter = DefaultLauncherWriter(
                dryRun = false,
                feedback = feedback,
                launcherDir = install // Use installDir for zilla script
            ),
            dryRun = false,
            verbose = debug,
            feedback = feedback
        )

        val result = installer.installFromTemplate(template)
        when (result) {
            is Either.Right<*> -> {
                logger.info("Installation successful: ${result.value}")
                echo("Installation successful: ${result.value}")
            }
            is Either.Left<*> -> {
                logger.error("Installation failed: ${result.value}")
                echo("Error: Installation failed: ${result.value}")
                throw RuntimeException("Installation failed: ${result.value}")
            }
        }
    }
}

class CleanCommand : CliktCommand(name = "clean") {
    private val installDir: String by option("--install-dir", "-d", help = "Installation directory").default(".m2")
    private val keepImage: Boolean by option("--keep-image", help = "Keep the runtime image").flag(default = false)
    private val logger = LoggerFactory.getLogger(CleanCommand::class.java)

    override fun run() {
        val install = Paths.get(installDir)
        val launcherDir = currentContext.findRoot().obj as? Path ?: Paths.get("")
        logger.info("Cleaning installation directory $install and launcher directory $launcherDir")
        echo("Cleaning installation directory $install and launcher directory $launcherDir")
        try {
            // Clean launcher directory
            if (Files.exists(launcherDir)) {
                Files.walk(launcherDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach { path ->
                        if (keepImage && path.toString().contains("zilla-install.jar")) {
                            logger.info("Keeping runtime image: $path")
                            echo("Keeping runtime image: $path")
                        } else {
                            Files.deleteIfExists(path)
                        }
                    }
                logger.info("Cleaned launcher directory: $launcherDir")
                echo("Cleaned launcher directory: $launcherDir")
            }

            // Clean installation directory
            if (Files.exists(install)) {
                Files.walk(install)
                    .sorted(Comparator.reverseOrder())
                    .forEach { path ->
                        if (keepImage && path.toString().contains("zilla-install.jar")) {
                            logger.info("Keeping runtime image: $path")
                            echo("Keeping runtime image: $path")
                        } else {
                            Files.deleteIfExists(path)
                        }
                    }
                logger.info("Cleaned installation directory: $install")
                echo("Cleaned installation directory: $install")
            } else {
                logger.warn("Installation directory does not exist: $install")
                echo("Warning: Installation directory does not exist: $install")
            }
        } catch (e: Exception) {
            logger.error("Failed to clean directories: ${e.message}")
            echo("Error: Failed to clean directories: ${e.message}")
            throw RuntimeException("Failed to clean directories", e)
        }
    }
}

fun main(args: Array<String>) {
    ZillaManager()
        .subcommands(WrapCommand(), InstallCommand(), CleanCommand())
        .main(args)
}