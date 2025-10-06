package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.aklivity.zilla.manager.internal.commands.install.LauncherWriter
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

class DefaultLauncherWriter(
    private val dryRun: Boolean = false,
    private val feedback: (String) -> Unit = { x -> println(x) },
    private val launcherDir: Path
) : LauncherWriter {

    override fun write(entryModule: String, outputDir: Path): Either<ZpmError, Path> = Either.catch {
        val launcherPath = launcherDir.resolve("zilla")
        val imagePath = outputDir.resolve("image")
        // Compute relative path from script's runtime directory to Java binary
        val javaBin = Paths.get(".zpm").resolve("image/bin/java").toString()

        if (dryRun) {
            feedback("🧪 [dry-run] Would write launcher for module $entryModule to $launcherPath")
            Files.createDirectories(launcherDir)
            Files.writeString(
                launcherPath,
                "#!/usr/bin/env bash\n# Dry-run launcher\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            Files.setPosixFilePermissions(
                launcherPath,
                PosixFilePermissions.fromString("rwxr-xr-x")
            )
            feedback("✅ [dry-run] Wrote launcher to $launcherPath")
            launcherPath.right()
        } else {
            feedback("📝 Writing launcher for module io.aklivity.zilla.runtime.command to $launcherPath")
            Files.createDirectories(launcherDir)

            // Read the shell script from resources
            val resourcePath = "scripts/zilla.sh"
            val scriptContent = this::class.java.classLoader.getResourceAsStream(resourcePath)?.use { input ->
                input.bufferedReader().readText()
            } ?: throw IllegalStateException("Resource not found: $resourcePath")

            // Replace placeholder with javaBin
            val launcherContent = scriptContent.replace("{javaBin}", javaBin)

            Files.writeString(
                launcherPath,
                launcherContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            Files.setPosixFilePermissions(
                launcherPath,
                PosixFilePermissions.fromString("rwxr-xr-x")
            )
            feedback("✅ Wrote launcher to $launcherPath")
            launcherPath.right()
        }
    }.mapLeft {
        feedback("❌ Failed to write launcher: ${it.message}")
        ZpmError.LauncherWriteError("Failed to write launcher: ${it.message}")
    } as Either<ZpmError, Path>
}