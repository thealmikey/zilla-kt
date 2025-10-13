package io.aklivity.zilla.manager.internal

import arrow.core.Either
import arrow.core.some
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.obj
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.aklivity.zilla.manager.internal.commands.install.MyZpmInstall
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmCacheKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmDependencyKt
import io.aklivity.zilla.manager.internal.commands.install.impl.*
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import kotlin.io.path.exists
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import org.eclipse.aether.repository.RemoteRepository

@Serializable
data class ZpmJson(
    val repositories: List<String>,
    val imports: List<String>,
    val dependencies: List<String>
)

class ZillaManager : CliktCommand(name = "zpm") {
    private val logger = LoggerFactory.getLogger(ZillaManager::class.java)
    private val launcherDir: String by option(
        "--launcher-directory",
        help = "launcher directory"
    ).default("zilla")

    override fun run() {
        logger.info("Zilla Package Manager v0.9.MikeVersion")
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
        val homeString = "\$HOME/.m2/repository/io/aklivity/zilla/manager/$version/$wrapperJarName"
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
            Files.createDirectories(launcherDir)
            Files.writeString(wrapperScriptPath, wrapperContent)
            try {
                Files.setPosixFilePermissions(
                    wrapperScriptPath,
                    PosixFilePermissions.fromString("rwxr-xr-x")
                )
            } catch (ex: UnsupportedOperationException) {
                wrapperScriptPath.toFile().setExecutable(true, false)
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
            cache = ZpmCacheKt(mutableListOf<RemoteRepository>(), install),
            installDir = install,
            jarCopier = JarCopier(dryRun = false, feedback = feedback),
            manifestMerger = ManifestMerger(dryRun = false, feedback = feedback),
            moduleInfoGenerator = ModuleInfoGenerator(dryRun = false, feedback = feedback),
            imageLinker = DefaultImageLinker(dryRun = false, feedback = feedback),
            launcherWriter = DefaultLauncherWriter(
                dryRun = false,
                feedback = feedback,
                launcherDir = install
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

class GenerateAssemblyCommand : CliktCommand(name = "generate-assembly") {
    private val outputPath: String by option(
        "--output",
        "-o",
        help = "Output path for assembly.xml"
    ).default("assembly.xml")
    private val zpmJsonPath: String by option(
        "--zpm-json",
        "-j",
        help = "Path to zpm.json file"
    ).default("zpm.json")
    private val debug: Boolean by option("--debug", help = "Enable debug logging").flag(default = false)
    private val logger = LoggerFactory.getLogger(GenerateAssemblyCommand::class.java)

    override fun run() {
        val output = Paths.get(outputPath)
        val zpmJson = Paths.get(zpmJsonPath)
        logger.info("Generating assembly.xml at $output using $zpmJson")
        echo("Generating assembly.xml at $output using $zpmJson")

        // Step 1: Read and parse zpm.json
        if (!zpmJson.exists()) {
            logger.error("zpm.json file not found: $zpmJson")
            echo("Error: zpm.json file not found: $zpmJson")
            throw RuntimeException("zpm.json file not found: $zpmJson")
        }

        val zpmJsonContent = zpmJson.toFile().readText()
        val zpmConfig = try {
            Json.decodeFromString<ZpmJson>(zpmJsonContent)
        } catch (e: Exception) {
            logger.error("Failed to parse zpm.json: ${e.message}")
            echo("Error: Failed to parse zpm.json: ${e.message}")
            throw RuntimeException("Failed to parse zpm.json", e)
        }

        // Step 2: Resolve dependencies using ZpmCacheKt
        val cache = ZpmCacheKt(
            repositories = zpmConfig.repositories.map {
                org.eclipse.aether.repository.RemoteRepository.Builder(it, "default", it).build()
            }.toMutableList(),
            localCacheDir = Paths.get(System.getProperty("user.home"), ".m2", "repository"),
            zpmCacheDir = Paths.get(System.getProperty("user.home"), ".zpm", "cache")
        )

        val imports = zpmConfig.imports.map { coords ->
            val parts = coords.split(":")
            ZpmDependencyKt(parts[0], parts[1], parts.getOrElse(2) { "develop-SNAPSHOT" }.some())
        }
        val dependencies = zpmConfig.dependencies.map { coords ->
            val parts = coords.split(":")
            if (parts.size > 2) {
                ZpmDependencyKt(parts[0], parts[1], parts[2].some())
            } else {
                ZpmDependencyKt(parts[0], parts[1], arrow.core.none())
            }
        }

        val resolvedArtifacts = when (val result = cache.resolveImports(imports, dependencies)) {
            is Either.Right -> result.value
            is Either.Left -> {
                logger.error("Failed to resolve dependencies: ${result.value}")
                echo("Error: Failed to resolve dependencies: ${result.value}")
                throw RuntimeException("Failed to resolve dependencies: ${result.value}")
            }
        }

        // Step 3: Generate includes with dynamic wildcard patterns
        val includes = mutableSetOf<String>()

        val groupedArtifacts = resolvedArtifacts.groupBy { artifact ->
            artifact.id.toString().split(":")[0] // e.g., io.aklivity.zilla
        }

        groupedArtifacts.forEach { (groupId, artifacts) ->
            val groupPath = groupId.replace(".", "/")
            if (groupId == "io.aklivity.zilla") {
                val artifactIds = artifacts.map { it.id.toString().split(":")[1] }
                val prefixes = artifactIds.mapNotNull { id ->
                    val dashIndex = id.indexOf('-')
                    if (dashIndex > 0) id.substring(0, dashIndex + 1) else null
                }.distinct()

                prefixes.forEach { prefix ->
                    if (artifactIds.count { it.startsWith(prefix) } > 1) {
                        includes.add("            <include>$groupPath/$prefix*/**</include>")
                    }
                }

                artifactIds.forEach { id ->
                    if (prefixes.none { id.startsWith(it) } ||
                        prefixes.any { id.startsWith(it) && artifactIds.count { it2 -> it2.startsWith(it) } == 1 }) {
                        includes.add("            <include>$groupPath/$id/**</include>")
                    }
                }
            } else {
                includes.add("            <include>$groupPath/**</include>")
            }
        }

        // --- NEW PATCH SECTION ---
        // Always include zilla manager and base imports (runtime, incubator, etc.)
        includes.add("            <include>io/aklivity/zilla/manager/**</include>")
        includes.add("            <include>io/aklivity/zilla/zilla/**</include>")

        zpmConfig.imports.forEach { imp ->
            val parts = imp.split(":")
            if (parts.size >= 2) {
                val path = parts[0].replace('.', '/') + "/" + parts[1]
                includes.add("            <include>$path/**</include>")
            }
        }
        // --- END PATCH SECTION ---

        // Step 4: Generate assembly.xml
        val assemblyContent = """
            |<assembly xmlns="http://maven.apache.org/ASSEMBLY/2.2.0"
            |          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            |          xsi:schemaLocation="http://maven.apache.org/ASSEMBLY/2.2.0 https://maven.apache.org/xsd/assembly-2.2.0.xsd">
            |    <id>zpm</id>
            |    <fileSets>
            |        <fileSet>
            |            <directory>${'$'}{settings.localRepository}</directory>
            |            <outputDirectory>./</outputDirectory>
            |            <useDefaultExcludes>false</useDefaultExcludes>
            |            <includes>
            |${includes.sorted().joinToString("\n")}
            |            </includes>
            |        </fileSet>
            |    </fileSets>
            |</assembly>
        """.trimMargin()

        try {
            Files.createDirectories(output.parent)
            Files.writeString(output, assemblyContent)
            logger.info("Generated assembly.xml with ${includes.size} includes at $output")
            echo("Generated assembly.xml with ${includes.size} includes at $output")
        } catch (e: Exception) {
            logger.error("Failed to write assembly.xml: ${e.message}")
            echo("Error: Failed to write assembly.xml: ${e.message}")
            throw RuntimeException("Failed to write assembly.xml", e)
        }
    }
}

fun main(args: Array<String>) {
    ZillaManager()
        .subcommands(WrapCommand(), InstallCommand(), CleanCommand(), GenerateAssemblyCommand())
        .main(args)
}