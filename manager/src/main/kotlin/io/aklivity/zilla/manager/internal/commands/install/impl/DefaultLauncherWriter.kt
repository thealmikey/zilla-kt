package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import io.aklivity.zilla.manager.internal.commands.install.LauncherWriter
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import java.nio.file.Files
import java.nio.file.Path

class DefaultLauncherWriter(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) : LauncherWriter {
    override fun write(mainClass: String, targetDir: Path): Either<ZpmError, Path> = Either.catch {
        val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val launcherPath = targetDir.resolve(if (isWindows) "zilla.bat" else "zilla")
        feedback?.invoke("📜 Generating launcher at $launcherPath")

        val script = if (isWindows) {
            """
            @echo off
            set JAVA_HOME=%~dp0image
            "%JAVA_HOME%\bin\java" -cp "%~dp0image\lib\*" $mainClass %*
            """.trimIndent()
        } else {
            """
            #!/bin/sh
            JAVA_HOME=${'$'}{0%/*}/image
            ${'$'}JAVA_HOME/bin/java -cp ${'$'}{0%/*}/image/lib/* $mainClass ${'$'}@
            """.trimIndent()
        }

        feedback?.invoke("📝 Script content:\n$script")
        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would write launcher: $launcherPath")
            Files.createDirectories(launcherPath.parent)
            Files.writeString(launcherPath, "// Dry-run launcher\n$script")
        } else {
            Files.createDirectories(launcherPath.parent)
            Files.writeString(launcherPath, script)
            if (!isWindows) {
                launcherPath.toFile().setExecutable(true, false)
            }
        }
        feedback?.invoke("✅ Created launcher: $launcherPath")
        launcherPath
    }.mapLeft {
        feedback?.invoke("❌ Failed to write launcher: ${it.message}")
        ZpmError.LauncherWriteError("Failed to write launcher: ${it.message}")
    }
}