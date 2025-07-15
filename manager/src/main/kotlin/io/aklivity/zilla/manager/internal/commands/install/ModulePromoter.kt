package io.aklivity.zilla.manager.internal.commands.install

interface ModulePromoter {
    fun promote(jar: Path): Either<ZpmError, Path>
}