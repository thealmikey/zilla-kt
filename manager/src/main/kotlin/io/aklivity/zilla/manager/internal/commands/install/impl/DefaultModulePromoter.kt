package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.model.PromotedModule
import java.nio.file.Path
import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.*

class DefaultModulePromoter: ModulePromoter{
    override fun promote(jar: Path):Either<ZpmError, PromotedModule>{
       return Either.catch{
            //inject module-info.class or generate synthetic module
            // val moduleInfo = generateModuleInfo(jar)
            val inferedName: String = jar.fileName.toString().removeSuffix(".jar")
            PromotedModule(inferedName, path = jar)
        }.mapLeft { error ->
            ZpmError.PromotionFailed("Failed to promote module from jar: ${jar.fileName} error , ${error.message}")
        }
    }
}