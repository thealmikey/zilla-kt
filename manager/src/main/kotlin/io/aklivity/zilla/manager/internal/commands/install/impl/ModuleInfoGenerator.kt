package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

class ModuleInfoGenerator(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) {
    fun generate(jarPath: Path, outputDir: Path, delegate: ZpmModuleKt): Either<ModuleInfoError, Path> {
        val moduleInfoDir = outputDir.resolve("module-info/zilla-install")
        val moduleInfoPath = moduleInfoDir.resolve("module-info.java")
        feedback?.let { it("📄 Generating module-info.java for $jarPath") }

        if (dryRun) {
            feedback?.let { it("📦 Would generate module-info.java for: $jarPath") }
            Files.createDirectories(moduleInfoDir)
            Files.writeString(moduleInfoPath, "module zilla.install { // Dry-run module-info }")
            feedback?.let { it("✅ Generated $moduleInfoPath") }
            return moduleInfoPath.right()
        }

        return try {
            Files.createDirectories(moduleInfoDir)
            feedback?.let { it ("📦 Generating module-info.java for: $jarPath") }
            val moduleInfoContent = buildString {
                appendLine("module zilla.install {")
                if (delegate.paths.isNotEmpty()) {
                    appendLine("    requires ${delegate.name};")
                    delegate.paths.forEach { path ->
                        appendLine("    // Delegated artifact: $path")
                    }
                } else {
                    appendLine("    // No delegate module")
                }
                JarFile(jarPath.toFile()).use { jarFile ->
                    appendLine("    // Classes from $jarPath:")
                    jarFile.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .forEach { appendLine("    // ${it.name}") }
                }
                appendLine("}")
            }
            Files.writeString(moduleInfoPath, moduleInfoContent)
            feedback?.let { it("✅ Generated $moduleInfoPath") }
            moduleInfoPath.right()
        } catch (e: Exception) {
            feedback?.let { it("❌ Error generating module-info: ${e.message}") }
            ModuleInfoError("Failed to generate module-info: ${e.message}").left()
        }
    }

    fun generateDelegate(delegate: ZpmModuleKt, outputDir: Path): Either<ModuleInfoError, Path> {
        val moduleInfoDir = outputDir.resolve("module-info/zilla-install")
        val moduleInfoPath = moduleInfoDir.resolve("module-info.java")
        feedback?.let { it ("📄 Generating delegate module-info.java for ${delegate.name}") }

        return try {
            Files.createDirectories(moduleInfoDir)
            val moduleInfoContent = buildString {
                appendLine("module ${delegate.name} {")
                delegate.paths.forEach { path ->
                    appendLine("    // Delegated artifact: $path")
                }
                appendLine("}")
            }
            Files.writeString(moduleInfoPath, moduleInfoContent)
            feedback?.let { it ("✅ Generated delegate $moduleInfoPath") }
            moduleInfoPath.right()
        } catch (e: Exception) {
            feedback?.let { it("❌ Error generating delegate module-info: ${e.message}") }
            ModuleInfoError("Failed to generate delegate module-info: ${e.message}").left()
        }
    }
}

data class ModuleInfoError(val message: String)