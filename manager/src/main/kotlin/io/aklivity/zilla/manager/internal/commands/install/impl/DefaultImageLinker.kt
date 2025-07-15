package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.ImageLinker
import java.nio.file.*
import arrow.core.*

class DefaultImageLinker: ImageLinker {
    override fun link(modules:List<Path>, output:Path):Either<ZpmError, Path>{
        Either.catch{
            val command = listOf(
                "jlink",
                "--module-path", modules.joinToString(":") { it.toString() },
                "--add-modules", modules.joinToString(",") { it.fileName.toString().removeSuffix(".jar") },
                "--output", output.toString()
            )
            val process = ProcessBuilder(command)
                .inheritIO()
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw ZpmError("jlink command failed with exit code $exitCode")     
            }
            output
        }.mapLeft { error ->
            ZpmError("Failed to link modules", error)
        }   
    }
}