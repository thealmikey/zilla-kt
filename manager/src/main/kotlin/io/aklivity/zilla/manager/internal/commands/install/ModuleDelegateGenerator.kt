package io.aklivity.zilla.manager.internal.commands.install


import java.nio.file.Path
import arrow.core.Either
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt

interface ModuleDelegateGenerator {
    fun generate(delegate: ZpmModuleKt): Either<ZpmError, Path>
}
