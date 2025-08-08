package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.*
import kotlin.io.path.outputStream

open class ManifestMerger(
        private val dryRun: Boolean = false,
        private val feedback: ((String) -> Unit)? = null
) {
    open fun merge(inputJars: List<Path>, outputFile: Path): Either<ZpmError, Path> =
        Either.catch {
            val merged = Manifest()
            val mainAttrs = merged.mainAttributes
            mainAttrs[Attributes.Name.MANIFEST_VERSION] = "1.0"

            if (dryRun) {
                feedback?.invoke("🧪 [dry-run] Would merge manifests from ${inputJars.size} jars into: $outputFile")
                return@catch outputFile
            }

            for (jarPath in inputJars) {
                JarFile(jarPath.toFile()).use { jar ->
                    val manifest = jar.manifest ?: return@use
                    for ((name, value) in manifest.mainAttributes) {
                        if (name.toString() != "Manifest-Version") {
                            mainAttrs.putIfAbsent(name, value)
                        }
                    }
                }
            }

            feedback?.invoke("📦 Merging manifest entries from ${inputJars.size} jars")
            Files.createDirectories(outputFile.parent)
            outputFile.outputStream().buffered().use { out -> merged.write(out) }

            feedback?.invoke("✅ Wrote merged manifest to: $outputFile")
            outputFile
        }.mapLeft {
            feedback?.invoke("💥 Error during manifest merge: ${it.message}")
            ZpmError.PackagingFailed(
                "Failed to merge manifests into $outputFile: ${it.message}"
            )
        }
}
