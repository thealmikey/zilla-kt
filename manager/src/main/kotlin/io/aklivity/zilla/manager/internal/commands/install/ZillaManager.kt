package io.aklivity.zilla.manager.internal

import arrow.core.Either
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.aklivity.zilla.manager.internal.commands.install.MyZpmInstall
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmCacheKt
import io.aklivity.zilla.manager.internal.commands.install.impl.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.exists

class ZillaManager : CliktCommand(name = "zpm") {
    private val logger = LoggerFactory.getLogger(ZillaManager::class.java)

    override fun run() {
        logger.info("Zilla Package Manager v0.9.88")
    }
}

class WrapCommand : CliktCommand(name = "wrap") {
    private val version: String by option("--version", help = "Zilla Manager version").default("0.9.88")
    private val logger = LoggerFactory.getLogger(WrapCommand::class.java)

    override fun run() {
        logger.info("Wrapping zpm command for version $version")
        val wrapperPath = Paths.get("zpmw")
        val wrapperContent = """
            #!/bin/bash
            java -jar .zpm/wrapper/manager-$version.jar "$@"
        """.trimIndent()
        try {
            Files.writeString(wrapperPath, wrapperContent)
            wrapperPath.toFile().setExecutable(true)
            logger.info("Created wrapper script: $wrapperPath")
            echo("Created wrapper script: $wrapperPath")
        } catch (e: Exception) {
            logger.error("Failed to create wrapper script: ${e.message}")
            echo("Error: Failed to create wrapper script: ${e.message}")
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
        logger.info("Installing from template $template to $install")
        echo("Installing from $template to $install")

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
            launcherWriter = DefaultLauncherWriter(dryRun = false, feedback = feedback),
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
    private val installDir: String by option("--install-dir", "-d", help = "Installation directory").default(".zpm")
    private val keepImage: Boolean by option("--keep-image", help = "Keep the runtime image").flag(default = false)
    private val logger = LoggerFactory.getLogger(CleanCommand::class.java)

    override fun run() {
        val install = Paths.get(installDir)
        logger.info("Cleaning installation directory $install")
        echo("Cleaning installation directory $install")
        try {
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
            logger.error("Failed to clean installation directory: ${e.message}")
            echo("Error: Failed to clean installation directory: ${e.message}")
            throw RuntimeException("Failed to clean installation directory", e)
        }
    }
}

fun main(args: Array<String>) {
    ZillaManager()
        .subcommands(WrapCommand(), InstallCommand(), CleanCommand())
        .main(args)
}