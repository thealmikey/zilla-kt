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
    private val feedback: (String) -> Unit = { x -> println(x) }
) : LauncherWriter {

    override fun write(entryModule: String, outputDir: Path): Either<ZpmError, Path> = Either.catch {
        val launcherPath = outputDir.resolve("zilla") // no .bat on Linux
        val imagePath = outputDir.resolve("image")

        if (dryRun) {
            feedback("🧪 [dry-run] Would write launcher for module $entryModule to $launcherPath")
            Files.createDirectories(outputDir)
            Files.writeString(
                launcherPath,
                "#!/usr/bin/env bash\n# Dry-run launcher\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            launcherPath.toFile().setExecutable(true)
            feedback("✅ [dry-run] Wrote launcher to $launcherPath")
            launcherPath.right()
        } else {
            feedback("📝 Writing launcher for module io.aklivity.zilla.runtime.command to $launcherPath")
            Files.createDirectories(outputDir)

            val javaBin = "image/bin/java"

            val launcherContent = """
    #!/usr/bin/env bash
    ZILLA_DIRECTORY="$(cd "$(dirname "$0")" && pwd)"
    exec "${'$'}ZILLA_DIRECTORY/$javaBin" \
      --add-reads org.agrona.core=jdk.unsupported \
      --module-path "${'$'}ZILLA_DIRECTORY/.zpm/modules" \
      -m io.aklivity.zilla.runtime.command/io.aklivity.zilla.runtime.command.internal.ZillaMain \
      "$@"
""".trimIndent()


            Files.writeString(
                launcherPath,
                launcherContent,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            launcherPath.toFile().setExecutable(true) // important on Linux
            feedback("✅ Wrote launcher to $launcherPath")
            launcherPath.right()
        }
    }.mapLeft {
        feedback("❌ Failed to write launcher: ${it.message}")
        ZpmError.LauncherWriteError("Failed to write launcher: ${it.message}")
    } as Either<ZpmError, Path>
}
