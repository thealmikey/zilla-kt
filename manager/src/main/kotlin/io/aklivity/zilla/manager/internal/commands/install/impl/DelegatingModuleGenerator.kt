package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.spi.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import org.slf4j.LoggerFactory
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

open class DefaultDelegatingModuleGenerator(
    private val installDir: Path,
    private val jarCopier: JarCopier,
    private val moduleInfoGenerator: ModuleInfoGenerator,
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) {
    private val logger = LoggerFactory.getLogger(DefaultDelegatingModuleGenerator::class.java)
    private val modulesDir: Path = installDir.resolve("modules")
    private val generatedDir: Path = installDir.resolve("generated")

//    open fun generateDelegating(
//        modules: Collection<ZpmModuleKt>,
//        feedback: ((String) -> Unit)?
//    ): Either<NonEmptyList<String>, Unit> {
//        val errors = mutableListOf<String>()
//        feedback?.invoke("📝 Generating stub JARs for delegating modules")
//        logger.debug("Generating stub JARs for modules: ${modules.map { it.name ?: it.id }}")
//        val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }
//        val generatedModulesDir = generatedDir.resolve("modules").createDirectories()
//
//        modules.filter { it.delegating && it.automatic && it.name != null }.forEach { module ->
//            try {
//                if (dryRun) {
//                    feedback?.invoke("🧪 [dry-run] Would generate stub JAR for ${module.name}")
//                    logger.debug("[dry-run] Would generate stub JAR for ${module.name}")
//                    return@forEach
//                }
//
//                val generatedModuleDir = generatedModulesDir.resolve(module.name!!).createDirectories()
//                val moduleInfoPath = moduleInfoGenerator.generateDelegate(module, generatedModulesDir).getOrElse { ex ->
//                    val error = "Failed to generate delegate module-info for ${module.name}: ${ex.message}"
//                    errors.add(error)
//                    feedback?.invoke("❌ $error")
//                    logger.error(error, ex)
//                    return@forEach
//                }
//                logger.debug("Generated delegate module-info at $moduleInfoPath")
//
//                val javacArgs = mutableListOf<String>()
//                if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
//                javacArgs.addAll(listOf("--module-path", modulesDir.toString(), "-d", generatedModuleDir.toString(), moduleInfoPath.toString()))
//                feedback?.invoke("📜 javac command for ${module.name}: ${javacArgs.joinToString(" ")}")
//                logger.debug("javac command: ${javacArgs.joinToString(" ")}")
//                val javacOut = ByteArrayOutputStream()
//                val javacErr = ByteArrayOutputStream()
//                val exitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
//                val javacErrStr = javacErr.toString(Charsets.UTF_8)
//                if (exitCode != 0) {
//                    val error = "javac failed for ${module.name}: $javacErrStr"
//                    errors.add(error)
//                    feedback?.invoke("❌ $error")
//                    logger.error(error)
//                    return@forEach
//                }
//                feedback?.invoke("✅ Compiled delegate module-info for ${module.name}")
//
//                val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
//                val multiReleaseModuleInfo = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
//                val (realModuleInfo, entryName) = when {
//                    moduleInfoClass.exists() -> moduleInfoClass to "module-info.class"
//                    multiReleaseModuleInfo.exists() -> multiReleaseModuleInfo to "META-INF/versions/9/module-info.class"
//                    else -> {
//                        val error = "module-info.class not found for ${module.name}"
//                        errors.add(error)
//                        feedback?.invoke("❌ $error")
//                        logger.error(error)
//                        return@forEach
//                    }
//                }
//
//                val modulePath = modulesDir.resolve("${module.name}.jar")
//                createEmptyJar(modulePath).getOrElse { ex ->
//                    val error = "Failed to create stub JAR for ${module.name}: ${ex.message}"
//                    errors.add(error)
//                    feedback?.invoke("❌ $error")
//                    logger.error(error, ex)
//                    return@forEach
//                }
//                jarExtender(modulePath, modulePath, JarEntry(entryName).apply { time = 318240000000L }, realModuleInfo).getOrElse { ex ->
//                    val error = "Failed to extend stub JAR with module-info for ${module.name}: ${ex.message}"
//                    errors.add(error)
//                    feedback?.invoke("❌ $error")
//                    logger.error(error, ex)
//                    return@forEach
//                }
//                feedback?.invoke("✅ Generated stub JAR for ${module.name} at $modulePath")
//                logger.debug("Generated stub JAR for ${module.name} at $modulePath")
//            } catch (e: Exception) {
//                val error = "Failed to process delegating module ${module.name}: ${e.message}"
//                errors.add(error)
//                feedback?.invoke("❌ $error")
//                logger.error(error, e)
//            }
//        }
//
//        feedback?.invoke("✅ Processed ${modules.count { it.delegating && it.automatic && it.name != null }} delegating modules")
//        return errors.toNonEmptyListOrNull()?.let { it.left() } ?: Unit.right()
//    }

    private fun createEmptyJar(target: Path): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("🛠 Creating empty JAR: $target")
        Files.createDirectories(target.parent)
        JarOutputStream(Files.newOutputStream(target)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
        feedback?.invoke("✅ Created empty JAR: $target")
        logger.debug("Created empty JAR: $target")
    }.mapLeft {
        feedback?.invoke("❌ Failed to create empty JAR: ${it.message}")
        logger.error("Failed to create empty JAR", it)
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to create empty JAR: ${it.message}")
    }

    private fun atLeastVersion(tool: ToolProvider, major: Int): Boolean {
        logger.debug("Checking if tool ${tool.name()} version >= $major")
        val out = ByteArrayOutputStream()
        tool.run(PrintStream(out), System.err, "--version")
        val matcher = Regex("""(\d+)\.""").find(out.toString(Charsets.UTF_8))
        val version = matcher?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        val atLeast = version >= major
        logger.debug("Tool version: $version, at least $major: $atLeast")
        return atLeast
    }

    private fun jarExtender(
        sourcePath: Path,
        targetPath: Path,
        entry: JarEntry,
        moduleInfoPath: Path
    ): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        JarOutputStream(Files.newOutputStream(targetPath)).use { jos ->
            JarFile(sourcePath.toFile()).use { jar ->
                jar.entries().asSequence().forEach { je ->
                    if (je.name != entry.name) {
                        jos.putNextEntry(JarEntry(je.name).apply { time = je.time })
                        jar.getInputStream(je).use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
                jos.putNextEntry(entry)
                Files.copy(moduleInfoPath, jos)
                jos.closeEntry()
            }
        }
    }.mapLeft { ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Failed to extend JAR") }
}