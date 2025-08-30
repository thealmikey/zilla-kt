package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.io.path.outputStream

open class ManifestMerger(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) {
    open fun merge(inputJars: List<Path>, outputFile: Path): Either<ZpmResolutionErrorKt, Path> =
        Either.catch {
            // Validate input JARs
            val validJars = inputJars.filter { jarPath ->
                when {
                    !Files.exists(jarPath) -> {
                        feedback?.invoke("⚠️ JAR $jarPath does not exist, skipping")
                        false
                    }
                    !Files.isReadable(jarPath) -> {
                        feedback?.invoke("⚠️ JAR $jarPath is not readable, skipping")
                        false
                    }
                    else -> true
                }
            }

            val merged = Manifest()
            val mainAttrs = merged.mainAttributes
            mainAttrs[Attributes.Name.MANIFEST_VERSION] = "1.0"

            if (dryRun) {
                feedback?.invoke("🧪 [dry-run] Would merge manifests from ${validJars.size} jars into: $outputFile")
                return@catch outputFile
            }

            for (jarPath in validJars) {
                try {
                    JarFile(jarPath.toFile()).use { jar ->
                        val manifest = jar.manifest ?: run {
                            feedback?.invoke("⚠️ No manifest in $jarPath, skipping")
                            return@use
                        }
                        // Merge main attributes
                        for ((name, value) in manifest.mainAttributes) {
                            if (name.toString() != "Manifest-Version") {
                                if (mainAttrs.containsKey(name)) {
                                    feedback?.invoke("⚠️ Duplicate main attribute $name in $jarPath, keeping first value")
                                } else {
                                    mainAttrs[name] = value
                                }
                            }
                        }
                        // Merge named sections
                        for ((sectionName, attrs) in manifest.entries) {
                            val existingAttrs = merged.getAttributes(sectionName) ?: Attributes()
                            for ((key, value) in attrs) {
                                if (existingAttrs.containsKey(key)) {
                                    feedback?.invoke("⚠️ Duplicate section attribute $key in $sectionName from $jarPath")
                                } else {
                                    existingAttrs[key] = value
                                }
                            }
                            merged.entries[sectionName] = existingAttrs
                        }
                    }
                } catch (e: IOException) {
                    feedback?.invoke("⚠️ Skipping invalid JAR $jarPath: ${e.message}")
                }
            }

            feedback?.invoke("📦 Merged manifest entries from ${validJars.size} jars")
            Files.createDirectories(outputFile.parent)
            outputFile.outputStream().buffered().use { out -> merged.write(out) }

            feedback?.invoke("✅ Wrote merged manifest to: $outputFile")
            outputFile
        }.mapLeft {
            feedback?.invoke("💥 Error during manifest merge: ${it.message}")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to merge manifests into $outputFile: ${it.message}")
        }
}