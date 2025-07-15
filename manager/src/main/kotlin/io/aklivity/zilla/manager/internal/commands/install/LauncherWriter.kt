package io.aklivity.zilla.manager.internal.commands.install

import java.nio.file.Path
import arrow.core.Either

interface LauncherWriter {
    fun write(entryModule:String, outputDir:Path): Either<ZpmError, Path>
}