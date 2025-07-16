package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.*
import java.nio.file.*
import arrow.core.*
import java.nio.file.attribute.PosixFilePermissions

class DefaultLauncherWriter : LauncherWriter {

    override fun write(entryModule: String, outputDir: Path): Either<ZpmError, Path> {
        return Either.catch {
            val launcherFile = outputDir.resolve("launcher.sh")
            Files.writeString(launcherFile, "#!/bin/sh\njava -jar $entryModule")
            Files.setPosixFilePermissions(launcherFile, PosixFilePermissions.fromString("rwxr-xr-x"))
            launcherFile
        }.mapLeft { ZpmError.LauncherError(it.message ?: "Unknown error") }
    }

}