package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.ModuleScanner
import java.nio.file.*
import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.*
import io.aklivity.zilla.manager.internal.commands.install.model.PromotedModule

class DefaultModuleScanner:ModuleScanner {
    override fun scan(path:Path):Either<ZpmError, List<Path>> =
        Either.catch{
            Files.walk(path)
            .filter{it.toString().endsWith(".jar")}
            .toList()
        }.mapLeft { error ->
            ZpmError.ModuleScanFailed(error)
        }
}