package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.ModulePackager
import io.aklivity.zilla.manager.internal.commands.install.model.PackagedModule
import java.nio.file.Path
import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.*

class DefaultModulePackager: ModulePackager {
    override fun packageAll(modules:List<Path>):Either<ZpmError,List<PackagedModule>>{
        return Either.catch{
            modules.map{
                PackagedModule(it.fileName.toString(), it)
            }
        }.mapLeft { error ->
            ZpmError.PackageFailed("Failed to package modules", error)
        }
    }
}