package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import java.util.spi.ToolProvider
import java.lang.module.ModuleFinder
import kotlin.io.path.extension

class DefaultImageLinker(
    private val dryRun: Boolean = false,
    private val feedback: (String) -> Unit = { x -> println(x) }
) {
    fun link(jar: Path, targetDir: Path): Either<ImageLinkError, Path> {
        val imagePath = targetDir.resolve("image")

        // 🧪 Dry-run mode (always succeed, but simulate steps)
        if (dryRun) {
            return Either.catch {
                feedback("🧪 [dry-run] Would run jlink with JAR: $jar → $imagePath")
                Files.createDirectories(imagePath)
                Files.writeString(imagePath.resolve("dry-run.txt"), "Dry-run image")
                feedback("✅ [dry-run] Created image at $imagePath")
                imagePath
            }.mapLeft { ImageLinkError("Dry-run failed: ${it.message}", cause = it) }
        }

        // 🔍 Locate jlink tool
        val jlink = ToolProvider.findFirst("jlink").orElse(null)
            ?: return ImageLinkError("jlink tool not found on system PATH").left()

        // 📦 Validate input jar
        if (!Files.exists(jar)) {
            return ImageLinkError("Input JAR not found: $jar").left()
        }
        if (!Files.isReadable(jar)) {
            return ImageLinkError("Input JAR is not readable: $jar").left()
        }

        // 📂 Validate module path
        val modulePath = jar.parent
        if (modulePath == null || !Files.exists(modulePath)) {
            return ImageLinkError("Module path not found: $modulePath").left()
        }

        // 📂 Collect module jars
        val moduleJars = Files.list(modulePath).use { stream ->
            stream.filter { it.extension == "jar" }.toList()
        }
        if (moduleJars.isEmpty()) {
            return ImageLinkError("No module JARs found in module path: $modulePath").left()
        }

        // 🧩 Extract module names
        val moduleNames = moduleJars.mapNotNull { jarFile ->
            JarFile(jarFile.toFile()).use { jf ->
                val descriptorName = runCatching {
                    ModuleFinder.of(jarFile).findAll().firstOrNull()?.descriptor()?.name()
                }.getOrElse { e ->
                    feedback("⚠️ Failed to load module descriptor for $jarFile: ${e.message}")
                    null
                }
                descriptorName
                    ?: jf.manifest?.mainAttributes?.getValue("Automatic-Module-Name")
                    ?: jarFile.fileName.toString().substringBeforeLast(".jar")
            }
        }.distinct().toMutableList()

        // Ensure zilla command module is included
        if ("io.aklivity.zilla.runtime.command" !in moduleNames) {
            feedback("⚠️ Adding io.aklivity.zilla.runtime.command to module list")
            moduleNames.add("io.aklivity.zilla.runtime.command")
        }

        // Add required system modules
        moduleNames.addAll(listOf("java.management", "jdk.management"))

        if (moduleNames.isEmpty()) {
            return ImageLinkError("No valid modules extracted from jars in $modulePath").left()
        }

        // ⚙️ Build jlink arguments
        val jlinkArgs = listOf(
            "--add-modules", moduleNames.joinToString(","),
            "--module-path", modulePath.toString(),
            "--output", imagePath.toString(),
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--compress=2",
            "--verbose"
        )

        feedback("🔗 Running jlink with modules: ${moduleNames.joinToString(", ")}")
        feedback("   Output → $imagePath")

        // ▶️ Run jlink
        val jlinkOut = ByteArrayOutputStream()
        val jlinkErr = ByteArrayOutputStream()
        val exitCode = jlink.run(
            PrintStream(jlinkOut),
            PrintStream(jlinkErr),
            *jlinkArgs.toTypedArray()
        )

        val stdout = jlinkOut.toString("UTF-8").trim()
        val stderr = jlinkErr.toString("UTF-8").trim()

        // ❌ jlink failed
        if (exitCode != 0) {
            return ImageLinkError(
                """
                jlink failed (exit=$exitCode)
                Modules: ${moduleNames.joinToString(", ")}
                Args: ${jlinkArgs.joinToString(" ")}
                STDOUT:
                $stdout
                STDERR:
                $stderr
                """.trimIndent()
            ).left()
        }

        // ❌ Image not created
        if (!Files.isDirectory(imagePath)) {
            return ImageLinkError(
                "jlink succeeded (exit=0) but image directory not created at: $imagePath"
            ).left()
        }

        // ✅ Success
        feedback("✅ Created image at: $imagePath")
        if (stdout.isNotEmpty()) feedback("ℹ️ jlink output:\n$stdout")
        if (stderr.isNotEmpty()) feedback("⚠️ jlink warnings:\n$stderr")

        return imagePath.right()
    }
}

data class ImageLinkError(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)
