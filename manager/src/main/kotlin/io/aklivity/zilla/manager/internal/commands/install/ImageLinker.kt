package io.aklivity.zilla.manager.internal.commands.install

import java.nio.file.Path
import arrow.core.Either

interface ImageLinker {
    fun link(modules: List<Path>, output:Path):Either<ZpmError,Path>
}