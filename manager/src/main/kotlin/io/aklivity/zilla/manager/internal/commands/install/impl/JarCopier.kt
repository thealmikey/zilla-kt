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
            feedback("Would copy ${jars.size} JARs to $outputJar")
            feedback("Would expand to modules/zilla-install")
            return outputJar.right()
        }

        val seenEntries = mutableSetOf<String>()
        return try {
            feedback("🛠 Copying ${jars.size} JARs to $outputJar")
            feedback("🛠 Writing JAR to: $outputJar")
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
            feedback("🛠 Extracting entries from $outputJar to modules/zilla-install")
            outputJar.right()
        } catch (e: Exception) {
            feedback("❌ Failed to copy JARs: ${e.message}")
            JarCopyError("Failed to copy JARs: ${e.message}").left()
        }
    }
}

data class JarCopyError(val message: String)