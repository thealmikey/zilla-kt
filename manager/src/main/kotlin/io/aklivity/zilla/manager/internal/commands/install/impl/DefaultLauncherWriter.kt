package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.aklivity.zilla.manager.internal.commands.install.LauncherWriter
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class DefaultLauncherWriter(
    private val dryRun: Boolean = false,
    private val feedback: (String) -> Unit = {x -> println(x)}
) : LauncherWriter {
    override fun write(entryModule: String, outputDir: Path): Either<ZpmError, Path> = Either.catch {
        val launcherPath = outputDir.resolve("zilla.bat")
        val imagePath = outputDir.resolve("image")
        if (dryRun) {
            feedback("🧪 [dry-run] Would write launcher for module $entryModule to $launcherPath")
            Files.createDirectories(outputDir)
            Files.writeString(launcherPath, "@echo off\nREM Dry-run launcher\n", StandardOpenOption.CREATE)
            feedback("✅ [dry-run] Wrote launcher to $launcherPath")
            launcherPath.right()
        } else {
            feedback("📝 Writing launcher for module io.aklivity.zilla.runtime.command to $launcherPath")
            Files.createDirectories(outputDir)
            val javaBin = if (System.getProperty("os.name").lowercase().contains("win"))
                "image\\bin\\java.exe" else "image/bin/java"
            val launcherContent = """
                @echo off
                set ZILLA_DIRECTORY=%~dp0
                %~dp0$javaBin --module-path %~dp0.zpm\modules -m io.aklivity.zilla.runtime.command/io.aklivity.zilla.runtime.command.internal.ZillaMain %*
            """.trimIndent()
            Files.writeString(launcherPath, launcherContent, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            feedback("✅ Wrote launcher to $launcherPath")
            launcherPath.right()
        }
    }.mapLeft {
        feedback("❌ Failed to write launcher: ${it.message}")
        ZpmError.LauncherWriteError("Failed to write launcher: ${it.message}")
    } as Either<ZpmError, Path>
}