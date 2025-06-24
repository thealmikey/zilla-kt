  package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.fsm

    sealed class UploadEvent {
        data class Begin(val traceId: Long) : UploadEvent()
        data class Data(val payload: ByteArray) : UploadEvent()
        data class Window(val credit: Int) : UploadEvent()
        object End : UploadEvent()
        data class Error(val reason: String) : UploadEvent()
    }
