package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.ModulePromoter
import io.aklivity.zilla.manager.internal.commands.install.model.PromotedModule
import java.nio.file.Path
import arrow.core.*

class DefaultModulePromoter: ModulePromoter{
    override fun promote(jar: Path):Either<ZpmError, PromotedModule>{
        Either.catch{
            //inject module-info.class or generate synthetic module
            PromotedModule(name = inferModuleName(jar), path = jar)
        }

        private fun inferModuleName(jar:Path): String =
            jar.fileName.toString().removeSuffix(".jar")
    }
}