package io.aklivity.zilla.manager.internal.commands.install.cache

sealed class ZpmResolutionErrorKt {
    data class ArtifactDescriptorError(val message: String, val cause: Throwable? = null) : ZpmResolutionErrorKt()
    data class DependencyResolutionError(val message: String, val cause: Throwable? = null) : ZpmResolutionErrorKt()
    data class MissingVersionError(val dependency: String) : ZpmResolutionErrorKt()
    data class ImportFailure(val message:String, val cause:Throwable? =  null): ZpmResolutionErrorKt()
}
