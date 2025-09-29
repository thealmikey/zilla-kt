package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream

import com.tinder.StateMachine
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.fsm.UploadEvent
import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.fsm.UploadState
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer
import io.aklivity.zilla.runtime.engine.internal.types.stream.*
import io.minio.MinioClient
import io.minio.PutObjectArgs
import org.agrona.DirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class ClaimCheckStreamFactory(
    private val context: EngineContext,
    private val config: ClaimCheckBindingConfig
) : BindingHandler {

    // Flyweight readers
    private val beginRO = BeginFW()
    private val dataRO = DataFW()
    private val endRO = EndFW()
    private val abortRO = AbortFW()
    private val windowRO = WindowFW()
    private val resetRO = ResetFW()

    private val streams = mutableMapOf<Long, StreamContext>()

    private val minio: MinioClient = MinioClient.builder()
        .endpoint(config.minio.endpoint)
        .credentials(config.minio.accessKey, config.minio.secretKey)
        .build()

    override fun newStream(
        msgTypeId: Int,
        buffer: DirectBuffer?,
        index: Int,
        length: Int,
        sender: MessageConsumer?
    ): MessageConsumer? {
        if (buffer == null || sender == null) return null

        val begin = beginRO.wrap(buffer, index, index + length)
        val streamId = begin.streamId()
        val traceId = begin.traceId()

        val pipe = PipedOutputStream()
        val input = PipedInputStream(pipe)

        val fsm = StateMachine.create<UploadState, UploadEvent, Unit> {
            initialState(UploadState.Idle)

            state(UploadState.Idle) {
                on<UploadEvent.Begin> {
                    beginUpload(traceId, input)
                    transitionTo(UploadState.Uploading)
                }
            }

            state(UploadState.Uploading) {
                on<UploadEvent.Data> {
                    pipe.write(it.payload)
                    transitionTo(UploadState.Uploading)
                }
                on<UploadEvent.Window> {
                    transitionTo(UploadState.Uploading)
                }
                on<UploadEvent.End> {
                    pipe.close()
                    transitionTo(UploadState.Completed)
                }
                on<UploadEvent.Error> {
                    pipe.close()
                    transitionTo(UploadState.Failed)
                }
            }

            state(UploadState.Completed) {
                onEnter {
                    emitClaimCreated(traceId)
                }
            }

            state(UploadState.Failed) {
                onEnter {
                    emitClaimFailed(traceId, "Upload failed")
                }
            }

            state(UploadState.Aborted) {
                onEnter {
                    emitClaimFailed(traceId, "Upload aborted")
                }
            }
        }

        val stream = StreamContext(streamId, traceId, pipe, input, fsm)
        streams[streamId] = stream

        fsm.transition(UploadEvent.Begin(traceId))

        return MessageConsumer { typeId, buf, idx, len ->
            handleFrame(streamId, typeId, buf, idx, len)
        }
    }

    private fun handleFrame(
        streamId: Long,
        msgTypeId: Int,
        buffer: DirectBuffer,
        index: Int,
        length: Int
    ) {
        val stream = streams[streamId] ?: return
        val fsm = stream.fsm

        when (msgTypeId) {
            DataFW.TYPE_ID -> {
                val data = dataRO.wrap(buffer, index, index + length)
                val payload = data.payload()
                val bytes = ByteArray(payload.sizeof())
                payload.buffer().getBytes(payload.offset(), bytes)

                if (stream.credit >= bytes.size) {
                    fsm.transition(UploadEvent.Data(bytes))
                    stream.uploadedSize += bytes.size
                    stream.credit -= bytes.size
                } else {
                    println("⚠️ Insufficient credit, dropping data or buffering needed")
                }
            }

            WindowFW.TYPE_ID -> {
                val window = windowRO.wrap(buffer, index, index + length)
                val credit = window.capabilities()
                stream.credit += credit
                fsm.transition(UploadEvent.Window(credit))
            }

            EndFW.TYPE_ID -> fsm.transition(UploadEvent.End)

            AbortFW.TYPE_ID, ResetFW.TYPE_ID -> {
                fsm.transition(UploadEvent.Error("Stream aborted or reset"))
            }
        }
    }

    private fun beginUpload(traceId: Long, input: PipedInputStream) {
        thread(start = true) {
            try {
                val key = "claim-$traceId.bin"
                minio.putObject(
                    PutObjectArgs.builder()
                        .bucket(config.minio.bucket)
                        .`object`(key)
                        .stream(input, -1, 5 * 1024 * 1024)
                        .build()
                )
                println("✅ Upload successful for $key")
            } catch (e: Exception) {
                streams.values.find { it.traceId == traceId }?.fsm?.transition(
                    UploadEvent.Error("MinIO error: ${e.message}")
                )
            }
        }
    }

    private fun emitClaimCreated(traceId: Long) {
        val uploadedSize = streams.values.find { it.traceId == traceId }?.uploadedSize ?: 0L
        println("📢 CLAIM_CREATED for traceId=$traceId size=${uploadedSize} bytes")
        // TODO: emit Zilla EventFW if needed
    }

    private fun emitClaimFailed(traceId: Long, reason: String) {
        println("📢 CLAIM_FAILED for traceId=$traceId: $reason")
        // TODO: emit Zilla EventFW if needed
    }
}
