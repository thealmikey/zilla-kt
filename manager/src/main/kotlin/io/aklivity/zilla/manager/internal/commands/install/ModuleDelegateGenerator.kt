package io.aklivity.zilla.manager.internal.commands.install


import io.aklivity.zilla.manager.internal.commands.install.model.ZpmModuleKt
import java.nio.file.Path
import arrow.core.Either

interface ModuleDelegateGenerator {
    fun generate(delegate: ZpmModuleKt): Either<ZpmError, Path>
}
