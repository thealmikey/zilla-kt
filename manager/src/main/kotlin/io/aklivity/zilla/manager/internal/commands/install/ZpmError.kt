package io.aklivity.zilla.manager.internal.commands.install

sealed class ZpmError: Exception() {
    data class LauncherError(override val message: String) : ZpmError()
    data class PromotionFailed(val reason: String) : ZpmError()
    data class JlinkError(override val message: String) : ZpmError()
    data class InvalidModule(override val message: String) : ZpmError()
    data class ModuleNotFound(val moduleName: String) : ZpmError()
    data class ModuleAlreadyExists(val moduleName: String) : ZpmError()
    data class InvalidPath(val path: String) : ZpmError()
    data class ModuleScanFailed(override val cause: Throwable) : ZpmError()
    data class PackagingFailed(override val message: String) : ZpmError()
    data class JarCopyError(override val message: String): ZpmError()
    data class ImageLinkError(override val message: String): ZpmError()
    data class LauncherWriteError(override val message: String): ZpmError()
}