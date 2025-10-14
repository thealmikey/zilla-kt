package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream

import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.*
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.String16FW
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.collections.Long2ObjectHashMap
import io.minio.MinioClient
import java.util.UUID
import java.util.function.LongUnaryOperator
import java.util.function.Predicate
import kotlin.math.max

/**
 * ClaimCheckStreamHandler — orchestrates per-HTTP-stream ClaimCheckHttpProcessor instances with dynamic buffer sizing and retries.
 */
class ClaimCheckStreamHandler(
    private val factory: ClaimCheckProxyFactory,
    private val defaultWriteBuffer: MutableDirectBuffer,
    private val defaultExtBuffer: MutableDirectBuffer,
    private val streamFactory: BindingHandler,
    private val httpTypeId: Int,
    private val supplyReplyId: LongUnaryOperator,
    private val context: EngineContext
) {

    companion object {
        val SUPPORTED_HTTP_METHOD: Predicate<HttpHeaderFW> = Predicate { header ->
            header.name().asString() == ":method" && run {
                val v = header.value().asString()
                v.equals("GET", true) || v.equals("POST", true) || v.equals("PUT", true)
            }
        }
        private const val MAX_RETRIES = 3
        private val BUFFER_SIZES = intArrayOf(4096, 8192, 65536) // Increased max size
    }

    private val beginRO = BeginFW()
    private val dataRO = DataFW()
    private val endRO = EndFW()
    private val abortRO = AbortFW()
    private val httpBeginExRO = HttpBeginExFW()
    private val httpResetExRO = HttpResetExFW()

    private val processors = Long2ObjectHashMap<ClaimCheckHttpProcessor>()

    init {
        println("ClaimCheckStreamHandler: Initialized with defaultWriteBuffer capacity=${defaultWriteBuffer.capacity()}, defaultExtBuffer capacity=${defaultExtBuffer.capacity()}")
    }

    fun onStream(
        msgTypeId: Int,
        buffer: DirectBuffer,
        offset: Int,
        length: Int,
        receiver: MessageConsumer,
        bindings: Long2ObjectHashMap<ClaimCheckBindingConfig>,
        minioClients: Long2ObjectHashMap<MinioClient>
    ) {
        val masked = msgTypeId and 0x3FFFFFFF
        try {
            when (masked) {
                BeginFW.TYPE_ID -> {
                    val begin = beginRO.wrap(buffer, offset, offset + length)
                    tryWithBufferSizes { writeBuffer, extBuffer ->
                        onBegin(receiver, begin, bindings, minioClients, writeBuffer, extBuffer)
                    }
                }

                DataFW.TYPE_ID -> {
                    val data = dataRO.wrap(buffer, offset, offset + length)
                    val sid = data.streamId()
                    val processor = processors[sid]
                    if (processor == null) {
                        println("ClaimCheckStreamHandler: Data for unknown stream $sid, ignoring")
                    } else {
                        println("ClaimCheckStreamHandler: onData(streamId=$sid, payloadSize=${data.payload().sizeof()})")
                        processor.onHttpData(data)
                    }
                }

                EndFW.TYPE_ID -> {
                    val end = endRO.wrap(buffer, offset, offset + length)
                    val sid = end.streamId()
                    val processor = processors.remove(sid)
                    if (processor != null) {
                        val peer = sid xor 1L
                        if (processors.containsKey(peer)) {
                            processors.remove(peer)
                        }
                        processor.onHttpEnd(end)
                        println("ClaimCheckStreamHandler: End processed for streamId=$sid")
                    } else {
                        println("ClaimCheckStreamHandler: End for unknown stream $sid, ignoring")
                    }
                }

                AbortFW.TYPE_ID -> {
                    val abort = abortRO.wrap(buffer, offset, offset + length)
                    val sid = abort.streamId()
                    val processor = processors.remove(sid)
                    if (processor != null) {
                        val peer = sid xor 1L
                        processors.remove(peer)
                        println("ClaimCheckStreamHandler: Stream aborted ($sid)")
                    } else {
                        println("ClaimCheckStreamHandler: Abort for unknown stream $sid, ignoring")
                    }
                }

                else -> {
                    println("ClaimCheckStreamHandler: Delegating unknown msgTypeId=$msgTypeId (masked=$masked) to streamFactory")
                    streamFactory.newStream(msgTypeId, buffer, offset, length, receiver)
                }
            }
        } catch (ex: Exception) {
            println("ClaimCheckStreamHandler: onStream error: ${ex.message}")
            ex.printStackTrace()
        }
    }

    private fun tryWithBufferSizes(action: (MutableDirectBuffer, MutableDirectBuffer) -> Unit) {
        var lastException: Exception? = null
        for (retry in 0 until MAX_RETRIES) {
            val bufferSize = if (retry < BUFFER_SIZES.size) BUFFER_SIZES[retry] else max(BUFFER_SIZES.last() * (retry - BUFFER_SIZES.size + 2), defaultWriteBuffer.capacity())
            val writeBuffer = if (bufferSize <= defaultWriteBuffer.capacity()) defaultWriteBuffer else context.writeBuffer()
            val extBuffer = if (bufferSize <= defaultExtBuffer.capacity()) defaultExtBuffer else UnsafeBuffer(ByteArray(bufferSize))

            println("ClaimCheckStreamHandler: Attempt ${retry + 1}/$MAX_RETRIES with writeBuffer size=${writeBuffer.capacity()}, extBuffer size=${extBuffer.capacity()}")

            try {
                action(writeBuffer, extBuffer)
                println("ClaimCheckStreamHandler: Successfully processed with buffer size=$bufferSize")
                return
            } catch (ex: IndexOutOfBoundsException) {
                println("ClaimCheckStreamHandler: Buffer size $bufferSize failed: ${ex.message}")
                lastException = ex
                if (retry == MAX_RETRIES - 1) {
                    println("ClaimCheckStreamHandler: Exhausted retries for buffer sizing")
                    throw lastException
                }
            }
        }
    }

    private fun onBegin(
        sender: MessageConsumer,
        begin: BeginFW,
        bindings: Long2ObjectHashMap<ClaimCheckBindingConfig>,
        minioClients: Long2ObjectHashMap<MinioClient>,
        writeBuffer: MutableDirectBuffer,
        extBuffer: MutableDirectBuffer
    ) {
        val originId = begin.originId()
        val routedId = begin.routedId()
        val initialId = begin.streamId()
        val sequence = begin.sequence()
        val acknowledge = begin.acknowledge()
        val maximum = begin.maximum()
        val traceId = begin.traceId()
        val authorization = begin.authorization()
        val extension = begin.extension()

        val correlationId = UUID.randomUUID().toString().substring(0, 8)
        println("ClaimCheckStreamHandler[$correlationId]: BeginFW { originId=$originId, routedId=$routedId, initialId=$initialId, traceId=$traceId }")
        println("ClaimCheckStreamHandler[$correlationId]: sequence=$sequence ack=$acknowledge max=$maximum")

        val binding = bindings[routedId]
        if (binding == null) {
            println("ClaimCheckStreamHandler[$correlationId]: No binding for routedId=$routedId, ignoring stream.")
            return
        }

        val beginEx = extension.get(httpBeginExRO::tryWrap)
        if (beginEx == null) {
            println("ClaimCheckStreamHandler[$correlationId]: No HttpBeginExFW extension, ignoring.")
            return
        }

        val headersDump = buildString {
            beginEx.headers().forEach { h ->
                if (isNotEmpty()) append(", ")
                append(h.name().asString()).append('=').append(h.value().asString())
            }
        }
        println("ClaimCheckStreamHandler[$correlationId]: Headers = [$headersDump]")

        val methodOk = beginEx.headers().anyMatch(SUPPORTED_HTTP_METHOD)
        if (!methodOk) {
            println("ClaimCheckStreamHandler[$correlationId]: Unsupported method -> sending 405")
            doHttpReset(sender, originId, routedId, initialId, sequence, acknowledge, maximum, traceId, ClaimCheckHttpProcessor.STATUS_405, writeBuffer, extBuffer)
            return
        }

        val route = binding.resolve(authorization, beginEx)
        if (route == null) {
            println("ClaimCheckStreamHandler[$correlationId]: No route -> sending 404")
            doHttpReset(sender, originId, routedId, initialId, sequence, acknowledge, maximum, traceId, ClaimCheckHttpProcessor.STATUS_404, writeBuffer, extBuffer)
            return
        }

        val minioClient = minioClients[routedId]
        if (minioClient == null) {
            println("ClaimCheckStreamHandler[$correlationId]: No MinIO client -> sending 500")
            doHttpReset(sender, originId, routedId, initialId, sequence, acknowledge, maximum, traceId, ClaimCheckHttpProcessor.STATUS_500, writeBuffer, extBuffer)
            return
        }

        try {
            val processor = ClaimCheckHttpProcessor(
                http = sender,
                originId = originId,
                routedId = routedId,
                initialId = initialId,
                sequence = sequence,
                acknowledge = acknowledge,
                maximum = maximum,
                traceId = traceId,
                authorization = authorization,
                binding = binding,
                route = route,
                minioClient = minioClient,
                httpTypeId = httpTypeId,
                supplyReplyId = supplyReplyId,
                writeBuffer = writeBuffer,
                extBuffer = extBuffer
            )

            processors.put(initialId, processor)
            processors.put(initialId xor 1L, processor)

            processor.onHttpBegin(begin)
            println("ClaimCheckStreamHandler[$correlationId]: Processor created for streamId=$initialId")

            // Send initial credit upstream (window)
            val credit = binding.routes.firstOrNull()?.with?.maxPayloadSize?.toInt() ?: 8192
            // Updated estimate: 9 longs (72 bytes) + 2 ints (8 bytes) + 32 bytes overhead
            val estimatedWindowSize = 9 * 8 + 2 * 4 + 32
            if (writeBuffer.capacity() < estimatedWindowSize) {
                println("ClaimCheckStreamHandler[$correlationId]: writeBuffer too small (${writeBuffer.capacity()} < $estimatedWindowSize)")
                doHttpReset(sender, originId, routedId, initialId, sequence, acknowledge, maximum, traceId, ClaimCheckHttpProcessor.STATUS_500, writeBuffer, extBuffer)
                return
            }

            val windowRW = WindowFW.Builder()
            val window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(initialId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(credit)
                .traceId(traceId)
                .budgetId(0L) // Default value
                .padding(0)   // Default value
                .capabilities(0) // Default value
                .build()
            println("ClaimCheckStreamHandler[$correlationId]: WindowFW fields: originId=$originId, routedId=$routedId, streamId=$initialId, sequence=$sequence, acknowledge=$acknowledge, maximum=$credit, traceId=$traceId, budgetId=0, padding=0, capabilities=0")
            sender.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof())
            println("ClaimCheckStreamHandler[$correlationId]: Sent initial WINDOW ($credit bytes, size=${window.sizeof()}) to upstream HTTP layer")
        } catch (ex: Exception) {
            println("ClaimCheckStreamHandler[$correlationId]: Error creating processor: ${ex.message}")
            ex.printStackTrace()
            doHttpReset(sender, originId, routedId, initialId, sequence, acknowledge, maximum, traceId, ClaimCheckHttpProcessor.STATUS_500, writeBuffer, extBuffer)
        }
    }

    private fun doHttpReset(
        sender: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        status: String16FW,
        writeBuffer: MutableDirectBuffer,
        extBuffer: MutableDirectBuffer
    ) {
        try {
            val estimatedResetSize = 8 * 7 + 64 // Increased overhead for safety
            if (writeBuffer.capacity() < estimatedResetSize || extBuffer.capacity() < estimatedResetSize) {
                println("ClaimCheckStreamHandler: Insufficient buffer for reset (write=${writeBuffer.capacity()}, ext=${extBuffer.capacity()}, needed=$estimatedResetSize)")
                return
            }

            val resetExRW = HttpResetExFW.Builder()
            val resetEx = resetExRW.wrap(extBuffer, 0, extBuffer.capacity())
                .typeId(httpTypeId)
                .headersItem { h -> h.name(ClaimCheckHttpProcessor.HEADER_STATUS_NAME).value(status) }
                .headersItem { h -> h.name(ClaimCheckHttpProcessor.HEADER_CONTENT_LENGTH_NAME).value("0") }
                .build()

            val resetRW = ResetFW.Builder()
            val reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .extension(resetEx.buffer(), resetEx.offset(), resetEx.sizeof())
                .build()

            sender.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof())
            println("ClaimCheckStreamHandler: Sent HTTP reset status=${status.asString()} for streamId=$streamId, size=${reset.sizeof()}")
        } catch (ex: Exception) {
            println("ClaimCheckStreamHandler: Failed to send reset: ${ex.message}")
            ex.printStackTrace()
        }
    }
}