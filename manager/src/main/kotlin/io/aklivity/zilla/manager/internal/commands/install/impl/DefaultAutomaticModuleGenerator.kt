package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.getOrElse
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.lang.module.ModuleDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.spi.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.text.Regex
import java.util.jar.JarFile
import kotlin.collections.filter
import kotlin.collections.toMutableSet
import kotlin.sequences.forEach

class DefaultAutomaticModuleGenerator(
    private val modulesDir: Path,
    private val generatedDir: Path,
    private val ignoreMissingDependencies: Boolean = true,
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null,
    private val moduleDescriptorLoader: (Path) -> ModuleDescriptor?,
    private val jarExtender: (Path, Path, JarEntry, Path) -> Either<ZpmResolutionErrorKt, Unit>
) {
    private val logger = LoggerFactory.getLogger(DefaultAutomaticModuleGenerator::class.java)

    fun generateSystemOnlyAutomatic(
        modules: Collection<ZpmModuleKt>
    ): Either<ZpmResolutionErrorKt, Set<ZpmModuleKt>> = Either.catch {
        feedback?.invoke("📝 Generating system-only automatic modules")
        logger.debug("Starting system-only automatic module generation")
        val generatedModulesDir = generatedDir.resolve("modules").createDirectories()
        val promotions = mutableMapOf<ZpmModuleKt, Path>()
        val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }
        val jdeps = ToolProvider.findFirst("jdeps").orElseThrow { IllegalStateException("jdeps not found") }

        for (module in modules.filter { it.automatic && it.depends.isEmpty() }) {
            val artifactPath = module.paths.first() ?: throw IOException("No artifact path for module ${module.name}")
            val validPackages = getValidPackages(artifactPath)
            feedback?.invoke("📦 Processing system-only automatic module: ${module.name}")
            logger.debug("Processing system-only automatic module: ${module.name}")
            val generatedModuleDir = generatedModulesDir.resolve(module.name ?: "unnamed").createDirectories()

            if (dryRun) {
                feedback?.invoke("🧪 [dry-run] Would generate module-info for ${module.name}")
                continue
            }

            val jdepsArgs = listOf(
                "--generate-open-module",
                generatedModuleDir.toString(),
                "--module-path",
                modulesDir.toString(),
                artifactPath.toString()
            )
            feedback?.invoke("📜 jdeps command for ${module.name}: ${jdepsArgs.joinToString(" ")}")
            val jdepsOut = ByteArrayOutputStream()
            val jdepsErr = ByteArrayOutputStream()
            val exitCode = jdeps.run(PrintStream(jdepsOut), PrintStream(jdepsErr), *jdepsArgs.toTypedArray())
            val jdepsOutStr = jdepsOut.toString(Charsets.UTF_8)
            val jdepsErrStr = jdepsErr.toString(Charsets.UTF_8)
            if (exitCode != 0 && !ignoreMissingDependencies) {
                throw IOException("jdeps failed for ${module.name}: $jdepsErrStr")
            }

            val generatedModuleInfo = generatedModuleDir.resolve("module-info.java")
            if (!generatedModuleInfo.exists()) {
                Files.writeString(
                    generatedModuleInfo,
                    "module ${module.name ?: "unnamed"} {\n    // Minimal module-info\n}"
                )
            } else {
                val content = Files.readString(generatedModuleInfo)
                val filtered = filterModuleInfo(content, validPackages)
                Files.writeString(generatedModuleInfo, filtered)
            }

            // Compile
            val javacArgs = mutableListOf<String>()
            if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
            javacArgs.addAll(listOf("--module-path", artifactPath.toString(), "-d", generatedModuleDir.toString(), generatedModuleInfo.toString()))
            val javacOut = ByteArrayOutputStream()
            val javacErr = ByteArrayOutputStream()
            val javacExitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
            if (javacExitCode != 0) {
                throw IOException("javac failed for ${module.name}")
            }

            val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
            val multiRelease = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
            val (realModuleInfo, entryName) = if (moduleInfoClass.exists()) moduleInfoClass to "module-info.class" else multiRelease to "META-INF/versions/9/module-info.class"

            val generatedModulePath = generatedModulesDir.resolve("${module.name}.jar")
            val moduleInfoEntry = JarEntry(entryName).apply { time = 318240000000L }
            jarExtender(artifactPath, generatedModulePath, moduleInfoEntry, realModuleInfo).getOrElse { throw it }
            promotions[module] = generatedModulePath
        }

        val updatedModules = modules.toMutableSet()
        updatedModules.removeAll(promotions.keys)
        promotions.forEach { (module, newPath) ->
            val descriptor = moduleDescriptorLoader(newPath) ?: throw IOException("Failed to load descriptor")
            val newArtifact = ZpmArtifactKt(module.id!!, newPath, emptySet())
            val newModule = ZpmModuleKt(descriptor = descriptor, artifact = newArtifact)
            updatedModules.add(newModule)
        }
        updatedModules
    }.mapLeft {
        ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error")
    }

    private fun getValidPackages(jarPath: Path): Set<String> = Either.catch {
        logger.debug("Scanning valid packages in JAR: $jarPath")
        val packages = mutableSetOf<String>()
        JarFile(jarPath.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") }
                .map { it.name.substringBeforeLast('/').replace('/', '.') }
                .filter { it.isNotBlank() }
                .forEach { packages.add(it) }
        }
        packages
    }.getOrElse {
        logger.error("Failed to scan packages in JAR $jarPath", it)
        emptySet()
    }

    // Helper method to filter module-info.java to include only valid packages
    private fun filterModuleInfo(content: String, validPackages: Set<String>): String {
        logger.debug("Filtering module-info.java with valid packages: $validPackages")
        val lines = content.lines()
        val filteredLines = lines.filter { line ->
            if (line.trim().startsWith("exports")) {
                val packageName = line.trim().substringAfter("exports").substringBefore(';').trim()
                validPackages.contains(packageName)
            } else {
                true // Keep non-export lines (e.g., module declaration, requires, comments)
            }
        }
        return filteredLines.joinToString("\n")
    }


    open fun atLeastVersion(tool: ToolProvider, major: Int): Boolean {
        logger.debug("Checking if tool ${tool.name()} version >= $major")
        val out = ByteArrayOutputStream()
        tool.run(PrintStream(out), System.err, "--version")
        val matcher = Regex("""(\d+)\.""").find(out.toString(Charsets.UTF_8))
        val version = matcher?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        val atLeast = version >= major
        logger.debug("Tool version: $version, at least $major: $atLeast")
        return atLeast
    }
}