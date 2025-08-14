package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.io.path.exists

class JarCopier(
    private val dryRun: Boolean,
    private val feedback: (String) -> Unit
) {
    fun copyJars(jars: List<Path>, outputJar: Path): Either<JarCopyError, Path> {
        if (dryRun) {
            feedback("🧪 [dry-run] Would copy ${jars.size} JARs to $outputJar")
            return outputJar.right()
        }

        // Validate input JARs
        val invalidJars = jars.filter { !Files.exists(it) || !Files.isReadable(it) }
        if (invalidJars.isNotEmpty()) {
            feedback("❌ Invalid JAR paths: ${invalidJars.joinToString()}")
            return JarCopyError("Invalid JAR paths: ${invalidJars.joinToString()}").left()
        }

        // Validate each JAR has .class files
        jars.forEach { jar ->
            JarFile(jar.toFile()).use { jarFile ->
                val hasClasses = jarFile.entries().asSequence().any { it.name.endsWith(".class") && !it.isDirectory }
                if (!hasClasses) {
                    feedback("❌ JAR contains no class files: $jar")
                    return JarCopyError("JAR contains no class files: $jar").left()
                }
            }
        }

        val seenEntries = mutableSetOf<String>()
        return try {
            feedback("🛠 Copying ${jars.size} JARs to $outputJar")
            Files.createDirectories(outputJar.parent)
            feedback("✅ Created parent directory: ${outputJar.parent}")
            JarOutputStream(Files.newOutputStream(outputJar)).use { out ->
                jars.forEach { jar ->
                    JarFile(jar.toFile()).use { jarFile ->
                        jarFile.entries().asSequence().forEach { entry ->
                            if (!entry.isDirectory && entry.name != "META-INF/MANIFEST.MF") {
                                if (seenEntries.add(entry.name)) {
                                    feedback("✅ Copied: ${entry.name}")
                                    out.putNextEntry(JarEntry(entry.name))
                                    jarFile.getInputStream(entry).use { it.copyTo(out) }
                                    out.closeEntry()
                                } else {
                                    feedback("⚠ Skipped duplicate entry: ${entry.name}")
                                }
                            }
                        }
                    }
                }
            }
            feedback("✅ Created $outputJar")
            Either.Right(outputJar)
        } catch (e: Exception) {
            feedback("❌ Failed to copy JARs: ${e.message}")
            JarCopyError("Failed to copy JARs: ${e.message}").left()
        }
    }
}

data class JarCopyError(val message: String)