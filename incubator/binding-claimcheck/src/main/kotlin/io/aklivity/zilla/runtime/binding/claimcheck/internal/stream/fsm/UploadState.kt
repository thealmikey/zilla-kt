package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.fsm

sealed class UploadState {
    object Idle : UploadState()
    object Uploading : UploadState()
    object Completed : UploadState()
    object Failed : UploadState()
    object Aborted: UploadState()
}
