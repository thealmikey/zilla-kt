package io.aklivity.zilla.manager.internal.commands.install

import java.nio.file.Path
import arrow.core.Either

interface ModuleScanner {
    fun scan(path: Path): Either<Throwable, List<Path>>
}
