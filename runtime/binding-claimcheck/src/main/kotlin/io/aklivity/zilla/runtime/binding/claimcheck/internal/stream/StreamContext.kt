package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream


import com.tinder.StateMachine
import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.fsm.UploadEvent
import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.fsm.UploadState
import java.io.PipedInputStream
import java.io.PipedOutputStream

data class StreamContext(
    val streamId: Long,
    val traceId: Long,
    val pipe: PipedOutputStream,
    val input: PipedInputStream,
    val fsm: StateMachine<UploadState, UploadEvent, Unit>,
    var uploadedSize:Int = 0,
    var credit: Int = 0
)
