package io.aklivity.zilla.manager.internal.commands.install

import io.aklivity.zilla.manager.internal.commands.install.ModulePackager
import io.aklivity.zilla.manager.internal.commands.install.model.PackagedModule
import java.nio.file.Path
import arrow.core.*

interface ModulePackager {
  fun packageAll(modules:List<Path>):Either<ZpmError,List<PackagedModule>>
}