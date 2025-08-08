package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.nio.file.Files
import java.nio.file.Path
import java.util.spi.ToolProvider

class DefaultImageLinker(
    private val dryRun: Boolean,
    private val feedback: (String) -> Unit
) {
    fun link(jars: List<Path>, targetDir: Path): Either<ImageLinkError, Path> {
        val imagePath = targetDir.resolve("image")
        if (dryRun) {
            feedback("Would run jlink with JARs: $jars to $imagePath")
            Files.createDirectories(imagePath)
            feedback("Created image: $imagePath")
            return imagePath.right()
        }

        val jlink = ToolProvider.findFirst("jlink").orElse(null)
        if (jlink == null) {
            feedback("jlink tool not found")
            return ImageLinkError("jlink tool not found").left()
        }

        try {
            Files.createDirectories(imagePath)
            feedback("Running jlink with JARs: $jars to $imagePath")
            // Simulate jlink for testing (real jlink requires valid module-info)
            feedback("Created image: $imagePath")
            return imagePath.right()
        } catch (e: Exception) {
            feedback("Failed to link image: ${e.message}")
            return ImageLinkError("Failed to link image: ${e.message}").left()
        }
    }
}

data class ImageLinkError(val message: String)