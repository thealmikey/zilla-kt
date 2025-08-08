package io.aklivity.zilla.manager.internal.commands.install.cache

sealed class ZpmResolutionErrorKt: Exception() {
    data class ArtifactNotFound(override val message:String, override val cause:Throwable? = null): ZpmResolutionErrorKt()
    data class ArtifactDescriptorError(override val message: String, override val cause: Throwable? = null) : ZpmResolutionErrorKt()
    data class DependencyResolutionError(override val message: String, override val cause: Throwable? = null) : ZpmResolutionErrorKt()
    data class MissingVersionError(val dependency: String) : ZpmResolutionErrorKt()
    data class ImportFailure(override val message:String, override val cause:Throwable? =  null): ZpmResolutionErrorKt()
    data class UnexpectedError(override val message: String?, override val cause: Throwable? =null) : ZpmResolutionErrorKt()
    data class InvalidDependencyError(override val message: String) : ZpmResolutionErrorKt()
    data class NoModulesFound(override val message: String)  : ZpmResolutionErrorKt()
}
