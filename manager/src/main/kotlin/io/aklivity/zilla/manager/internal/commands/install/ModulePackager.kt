package io.aklivity.zilla.manager.internal.commands.install

import io.aklivity.zilla.manager.internal.commands.install.ModulePackager
import io.aklivity.zilla.manager.internal.commands.install.model.PackagedModule
import java.nio.file.Path
import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.ZpmError

fun interface ModulePackager {
    fun packageModule(inputJars: List<Path>, output: Path): Either<ZpmError, Path>
}