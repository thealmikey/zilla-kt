package io.aklivity.zilla.manager.internal.commands.install

sealed interface ZpmError {
    data class LauncherError(val message: String) : ZpmError
    data class PromotionFailed(val reason: String) : ZpmError
    data class JlinkError(val message: String) : ZpmError
    data class InvalidModule(val message: String) : ZpmError
    data class ModuleNotFound(val moduleName: String) : ZpmError
    data class ModuleAlreadyExists(val moduleName: String) : ZpmError
    data class InvalidPath(val path: String) : ZpmError
    data class ModuleScanFailed(val cause: Throwable) : ZpmError
    data class PackagingFailed(val message: String) : ZpmError
    data class JarCopyError(val message: String): ZpmError
    data class ImageLinkError(val message: String): ZpmError
    data class LauncherWriteError(val message: String): ZpmError
}