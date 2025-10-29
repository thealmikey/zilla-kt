package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream

import io.aklivity.zilla.runtime.binding.claimcheck.config.ClaimCheckWithConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckConfiguration
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckOptionsConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.Flyweight
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.OctetsFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.String8FW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.String16FW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.AbortFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.BeginFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.DataFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.EndFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.FlushFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.HttpBeginExFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.HttpResetExFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.ResetFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.WindowFW
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer
import io.aklivity.zilla.runtime.engine.config.BindingConfig
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.collections.Long2ObjectHashMap
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.http.Method
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.function.LongUnaryOperator
import java.util.function.Predicate
import java.lang.System as System

class ClaimCheckProxyFactory(
    private val config: ClaimCheckConfiguration,
    private val context: EngineContext
) : BindingHandler {

    private companion object {
        const val HTTP_TYPE_NAME = "http"
        const val CLAIMCHECK_TYPE_NAME = "claimcheck"
        const val REQUEST_TIMEOUT_MS = 30000L // 30 seconds timeout for receiving data
        const val MIN_PART_SIZE = 5 * 1024 * 1024L // S3 minimum multipart part size is 5MiB
        const val DEFAULT_PART_SIZE = 8 * 1024 * 1024L // prefer 8MiB parts for balance

        val HEADER_STATUS_NAME = String8FW(":status")
        val HEADER_METHOD_NAME = String8FW(":method")
        val HEADER_CONTENT_TYPE_NAME = String8FW("content-type")
        val HEADER_CONTENT_LENGTH_NAME = String8FW("content-length")
        val HEADER_X_CLAIM_NAME = String8FW("X-Claim")
        val HEADER_STATUS_VALUE_200 = String16FW("200")
        val HEADER_STATUS_VALUE_400 = String16FW("400")
        val HEADER_STATUS_VALUE_405 = String16FW("405")
        val HEADER_STATUS_VALUE_408 = String16FW("408") // Request Timeout
        val HEADER_STATUS_VALUE_413 = String16FW("413")
        val HEADER_STATUS_VALUE_500 = String16FW("500")
        val EMPTY_EXTENSION = OctetsFW().wrap(UnsafeBuffer(ByteArray(0)), 0, 0)

        val SUPPORTED_HTTP_METHOD: Predicate<HttpHeaderFW> = Predicate { header ->
            header.name().equals(HEADER_METHOD_NAME) &&
                    (header.value().asString() == "POST" || header.value().asString() == "PUT")
        }

        init {
            println("ClaimCheckProxyFactory: Initializing companion object")
            try {
                println("ClaimCheckProxyFactory: Companion object initialized successfully")
            } catch (e: Exception) {
                println("ClaimCheckProxyFactory: Error initializing companion object")
                e.printStackTrace()
            }
        }
    }

    private val writeBuffer: MutableDirectBuffer = context.writeBuffer()
    private val extBuffer: MutableDirectBuffer = UnsafeBuffer(ByteArray(context.writeBuffer().capacity()))
    private val streamFactory: BindingHandler = context.streamFactory()
    private val supplyInitialId: LongUnaryOperator = LongUnaryOperator { value -> context.supplyInitialId(value) }
    private val supplyReplyId: LongUnaryOperator = LongUnaryOperator { value -> context.supplyReplyId(value) }
    private val httpTypeId: Int = context.supplyTypeId(HTTP_TYPE_NAME)
    private val bindings = Long2ObjectHashMap<ClaimCheckBindingConfig>()

    private val beginRO = BeginFW()
    private val dataRO = DataFW()
    private val endRO = EndFW()
    private val abortRO = AbortFW()
    private val flushRO = FlushFW()
    private val windowRO = WindowFW()
    private val resetRO = ResetFW()
    private val beginRW = BeginFW.Builder()
    private val dataRW = DataFW.Builder()
    private val endRW = EndFW.Builder()
    private val abortRW = AbortFW.Builder()
    private val flushRW = FlushFW.Builder()
    private val windowRW = WindowFW.Builder()
    private val resetRW = ResetFW.Builder()
    private val httpBeginExRO = HttpBeginExFW()
    private val httpBeginExRW = HttpBeginExFW.Builder()
    private val httpResetExRW = HttpResetExFW.Builder()

    fun attach(binding: BindingConfig) {
        println("ClaimCheckProxyFactory: Entering attach(bindingId=${binding.id})")
        try {
            println("ClaimCheckProxyFactory: Current bindings: ${bindings.keys}")
            if (bindings.containsKey(binding.id)) {
                println("ClaimCheckProxyFactory: Binding already exists for bindingId=${binding.id}, skipping")
            } else {
                bindings[binding.id] = ClaimCheckBindingConfig(binding)
                println("ClaimCheckProxyFactory: Binding attached successfully for bindingId=${binding.id}")
            }
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in attach for bindingId=${binding.id}")
            e.printStackTrace()
        }
        println("ClaimCheckProxyFactory: Exiting attach")
    }

    fun detach(bindingId: Long) {
        println("ClaimCheckProxyFactory: Entering detach(bindingId=$bindingId)")
        try {
            if (bindings.containsKey(bindingId)) {
                bindings.remove(bindingId)
                println("ClaimCheckProxyFactory: Removed bindingId=$bindingId, remaining bindings: ${bindings.keys}")
            } else {
                println("ClaimCheckProxyFactory: No binding found for bindingId=$bindingId to detach")
            }
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error detaching bindingId=$bindingId")
            e.printStackTrace()
        }
        println("ClaimCheckProxyFactory: Exiting detach")
    }

    override fun newStream(
        msgTypeId: Int,
        buffer: DirectBuffer,
        index: Int,
        length: Int,
        sender: MessageConsumer
    ): MessageConsumer? {
        if (msgTypeId != BeginFW.TYPE_ID) {
            println("ClaimCheckProxyFactory: Unexpected msgTypeId=$msgTypeId, ignoring")
            return null
        }

        val begin = beginRO.wrap(buffer, index, index + length)

        val originId = begin.originId()
        val routedId = begin.routedId()
        val initialId = begin.streamId()
        val sequence = begin.sequence()
        val acknowledge = begin.acknowledge()
        val maximum = begin.maximum()
        val traceId = begin.traceId()
        val authorization = begin.authorization()
        val affinity = begin.affinity()
        val extension = begin.extension()

        val correlationId = UUID.randomUUID().toString().substring(0, 8)

        println(
            "ClaimCheckProxyFactory[$correlationId]: BeginFW { " +
                    "originId=$originId, routedId=$routedId, initialId=$initialId, " +
                    "seq=$sequence, ack=$acknowledge, max=$maximum, " +
                    "traceId=$traceId, auth=$authorization, affinity=$affinity, " +
                    "ext.size=${extension.sizeof()} }"
        )

        val binding = bindings[routedId]
        if (binding == null) {
            println("ClaimCheckProxyFactory[$correlationId]: No binding found for routedId=$routedId. Available=${bindings.keys}. Ignoring stream.")
            return null
        }

        val beginEx = extension.get(httpBeginExRO::tryWrap)
        if (beginEx == null) {
            println("ClaimCheckProxyFactory[$correlationId]: No HttpBeginExFW extension, ignoring stream (soft fail).")
            return null
        }

        val headersDump = buildString {
            beginEx.headers().forEach { h ->
                if (isNotEmpty()) append(", ")
                append(h.name().asString()).append('=').append(h.value().asString())
            }
        }
        println("ClaimCheckProxyFactory[$correlationId]: Headers = [$headersDump]")

        val methodOk = beginEx.headers().anyMatch(SUPPORTED_HTTP_METHOD)
        if (!methodOk) {
            println("ClaimCheckProxyFactory[$correlationId]: Unsupported or missing HTTP method. Resetting with 405.")
            doHttpReset(sender, originId, routedId, initialId, sequence, acknowledge, maximum, traceId, HEADER_STATUS_VALUE_405)
            return null
        }

        val route = binding.resolve(authorization, beginEx)
        if (route == null) {
            println("ClaimCheckProxyFactory[$correlationId]: Route resolution failed. Ignoring stream (soft fail). Headers=$headersDump auth=$authorization")
            return null
        }
        println("ClaimCheckProxyFactory[$correlationId]: Route resolved, routeId=${route.id}")

        val resolved = route.with
        if (resolved == null) {
            println("ClaimCheckProxyFactory[$correlationId]: Resolved config missing. Ignoring stream (soft fail).")
            return null
        }
        println("ClaimCheckProxyFactory[$correlationId]: Resolved config ok (ttl=${resolved.ttl}, maxPayloadSize=${resolved.maxPayloadSize})")

        return try {
            val httpProxy = HttpProxy(
                http = sender,
                originId = originId,
                routedId = routedId,
                initialId = initialId,
                resolvedId = route.id,
                resolved = resolved,
                binding = binding
            )
            val result = httpProxy.newStream()
            println("ClaimCheckProxyFactory[$correlationId]: newStream created successfully.")
            result
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory[$correlationId]: ERROR in newStream (originId=$originId, routedId=$routedId, initialId=$initialId, traceId=$traceId): ${e.message}")
            e.printStackTrace()
            doHttpReset(sender, originId, routedId, initialId, sequence, acknowledge, maximum, traceId, HEADER_STATUS_VALUE_500)
            null
        }
    }

    private inner class HttpProxy(
        private val http: MessageConsumer,
        private val originId: Long,
        private val routedId: Long,
        private val initialId: Long,
        private val resolvedId: Long,
        private val resolved: ClaimCheckWithConfig,
        private val binding: ClaimCheckBindingConfig
    ) {
        private val replyId: Long = supplyReplyId.applyAsLong(initialId)
        private var state: Int = 0
        private var initialSeq: Long = 0
        private var initialAck: Long = 0
        private var initialMax: Int = 0
        private var replySeq: Long = 0
        private var replyAck: Long = 0
        private var replyMax: Int = 0

        // Streaming-related
        private var totalSize: Long = 0
        private var expectedContentLength: Long? = null
        private val uploadError: AtomicReference<Throwable?> = AtomicReference(null)
        private val done = AtomicBoolean(false)
        private var queue: LinkedBlockingQueue<ByteArray>? = null
        private var stream: QueueInputStream? = null
        private var uploadThread: Thread? = null
        private var uploadStarted: Boolean = false
        private var claimKey: String? = null

        private val minioClient: MinioClient
        private val options: ClaimCheckOptionsConfig
        private var lastActivityTime: Long = 0

        init {
            println("HttpProxy: Entering constructor for initialId=$initialId")
            try {
                options = binding.options
                minioClient = MinioClient.builder()
                    .endpoint(options.endpoint)
                    .credentials(options.accessKey, options.secretKey)
                    .build()
                println("HttpProxy: MinioClient initialized successfully")
            } catch (e: Exception) {
                println("HttpProxy: Error initializing HttpProxy for initialId=$initialId")
                e.printStackTrace()
                throw e
            }
            println("HttpProxy: Exiting constructor")
        }

        fun newStream(): MessageConsumer {
            println("HttpProxy: Entering newStream(initialId=$initialId)")
            try {
                val result = MessageConsumer { t, b, i, l ->
                    onHttpMessage(t, b, i, l)
                }
                println("HttpProxy: Exiting newStream, returning new MessageConsumer")
                return result
            } catch (e: Exception) {
                println("HttpProxy: Error in newStream for initialId=$initialId")
                e.printStackTrace()
                throw e
            }
        }

        private fun onHttpMessage(
            msgTypeId: Int,
            buffer: DirectBuffer,
            index: Int,
            length: Int
        ) {
            println("HttpProxy: Entering onHttpMessage(msgTypeId=$msgTypeId, initialId=$initialId)")
            try {
                // Check for timeout
                if (msgTypeId != WindowFW.TYPE_ID && lastActivityTime > 0 && System.currentTimeMillis() - lastActivityTime > REQUEST_TIMEOUT_MS) {
                    println("HttpProxy: Request timeout after ${REQUEST_TIMEOUT_MS}ms, sending 408")
                    doHttpReset(windowRO.wrap(buffer, index, index + length).traceId(), HEADER_STATUS_VALUE_408)
                    cleanupStreaming()
                    return
                }

                when (msgTypeId) {
                    BeginFW.TYPE_ID -> {
                        val begin = beginRO.wrap(buffer, index, index + length)
                        onHttpBegin(begin)
                    }
                    DataFW.TYPE_ID -> {
                        val data = dataRO.wrap(buffer, index, index + length)
                        onHttpData(data)
                    }
                    EndFW.TYPE_ID -> {
                        val end = endRO.wrap(buffer, index, index + length)
                        onHttpEnd(end)
                    }
                    AbortFW.TYPE_ID -> {
                        val abort = abortRO.wrap(buffer, index, index + length)
                        onHttpAbort(abort)
                    }
                    ResetFW.TYPE_ID -> {
                        val reset = resetRO.wrap(buffer, index, index + length)
                        onHttpReset(reset)
                    }
                    WindowFW.TYPE_ID -> {
                        val window = windowRO.wrap(buffer, index, index + length)
                        onHttpWindow(window)
                    }
                    FlushFW.TYPE_ID -> {
                        val flush = flushRO.wrap(buffer, index, index + length)
                        onHttpFlush(flush)
                    }
                }
                println("HttpProxy: Exiting onHttpMessage for msgTypeId=$msgTypeId")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpMessage for msgTypeId=$msgTypeId: ${e.message}")
                e.printStackTrace()
                doHttpReset(windowRO.wrap(buffer, index, index + length).traceId(), HEADER_STATUS_VALUE_500)
            }
        }

        private fun onHttpBegin(begin: BeginFW) {
            println("HttpProxy: Entering onHttpBegin(initialId=$initialId)")
            try {
                val sequence = begin.sequence()
                val acknowledge = begin.acknowledge()
                val traceId = begin.traceId()
                val authorization = begin.authorization()
                val extension = begin.extension()

                // Attempt to parse Content-Length from HTTP headers
                extension.get(httpBeginExRO::tryWrap)?.let { beginEx ->
                    beginEx.headers().forEach { h ->
                        val name = h.name().asString()
                        if (name.equals("content-length", ignoreCase = true)) {
                            runCatching { h.value().asString().toLong() }
                                .onSuccess { expectedContentLength = it }
                                .onFailure { /* ignore parse error */ }
                        }
                    }
                }

                initialSeq = sequence
                initialAck = acknowledge
                state = ClaimCheckState.openingInitial(state)
                lastActivityTime = System.currentTimeMillis()
                initialMax = resolved.maxPayloadSize.toInt().coerceAtLeast(8192)
                doHttpWindow(authorization, traceId, 0L, 0, 0)
                println("HttpProxy: Initial window sent with initialMax=$initialMax")
                println("HttpProxy: Exiting onHttpBegin")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpBegin for initialId=$initialId: ${e.message}")
                e.printStackTrace()
                doHttpReset(begin.traceId(), HEADER_STATUS_VALUE_500)
            }
        }

        private fun ensureUploadStarted(traceId: Long) {
            if (uploadStarted) return
            println("HttpProxy: ensureUploadStarted() - starting streaming upload for initialId=$initialId")
            uploadStarted = true
            claimKey = UUID.randomUUID().toString()
            val queueCapacityBytes = resolved.inMemoryThreshold.coerceAtLeast(DEFAULT_PART_SIZE) // use threshold as buffer budget
            queue = LinkedBlockingQueue()
            stream = QueueInputStream(queue!!, done)
            val partSize = DEFAULT_PART_SIZE.coerceAtLeast(MIN_PART_SIZE)
            val objectName = claimKey!!

            uploadThread = Thread({
                try {
                    println("HttpProxy: Uploader thread started for claimKey=$objectName")
                    minioClient.putObject(
                        PutObjectArgs.builder()
                            .bucket(options.bucket)
                            .`object`(objectName)
                            .stream(stream as InputStream, -1, partSize)
                            .build()
                    )
                    println("HttpProxy: Uploader thread completed successfully for claimKey=$objectName")
                } catch (t: Throwable) {
                    println("HttpProxy: Uploader thread error for claimKey=$objectName: ${t.message}")
                    t.printStackTrace()
                    uploadError.set(t)
                }
            }, "claimcheck-upload-$initialId")
            uploadThread!!.isDaemon = true
            uploadThread!!.start()
        }

        private fun onHttpData(data: DataFW) {
            println("HttpProxy: Entering onHttpData(initialId=$initialId)")
            try {
                val sequence = data.sequence()
                val acknowledge = data.acknowledge()
                val traceId = data.traceId()
                val authorization = data.authorization()
                val reserved = data.reserved()
                val payload = data.payload()

                initialSeq = sequence + reserved
                lastActivityTime = System.currentTimeMillis()

                val size = payload.sizeof()
                totalSize += size
                println("HttpProxy: Received DataFW: size=$size, totalSize=$totalSize, seq=$sequence, ack=$acknowledge, reserved=$reserved")

                if (totalSize > resolved.maxPayloadSize) {
                    println("HttpProxy: Total size $totalSize exceeds maxPayloadSize=${resolved.maxPayloadSize}, sending 413")
                    doHttpReset(traceId, HEADER_STATUS_VALUE_413)
                    cleanupStreaming()
                    return
                }

                ensureUploadStarted(traceId)

                // copy payload bytes and enqueue for streaming upload
                val bytes = ByteArray(size)
                payload.buffer().getBytes(payload.offset(), bytes)
                // blocking put will provide natural backpressure via this worker thread
                queue!!.put(bytes)

                // check if upload failed asynchronously
                uploadError.get()?.let {
                    println("HttpProxy: Detected upload error during onHttpData: ${it.message}")
                    doHttpReset(traceId, HEADER_STATUS_VALUE_500)
                    cleanupStreaming()
                    return
                }

                // Update window to allow more data
                initialAck = initialSeq
                initialMax = resolved.maxPayloadSize.toInt().coerceAtLeast(8192)
                doHttpWindow(authorization, traceId, 0L, reserved, 0)
                println("HttpProxy: Window updated: initialAck=$initialAck, initialMax=$initialMax")
                println("HttpProxy: Exiting onHttpData")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpData for initialId=$initialId: ${e.message}")
                e.printStackTrace()
                doHttpReset(data.traceId(), HEADER_STATUS_VALUE_500)
                cleanupStreaming()
            }
        }

        private fun onHttpEnd(end: EndFW) {
            println("HttpProxy: Entering onHttpEnd(initialId=$initialId)")
            try {
                val sequence = end.sequence()
                val acknowledge = end.acknowledge()
                val traceId = end.traceId()
                val authorization = end.authorization()

                initialSeq = sequence
                state = ClaimCheckState.closeInitial(state)
                lastActivityTime = System.currentTimeMillis()

                // Validate total size against expected Content-Length
                if (expectedContentLength != null && totalSize != expectedContentLength) {
                    println("HttpProxy: Total size $totalSize does not match Content-Length $expectedContentLength, sending 400")
                    doHttpReset(traceId, HEADER_STATUS_VALUE_400)
                    cleanupStreaming()
                    return
                }

                // if no data was received, still create empty upload
                if (!uploadStarted) {
                    ensureUploadStarted(traceId)
                }

                // finish queue/input stream and join uploader
                done.set(true)
                stream?.finish()
                // join with timeout to avoid indefinite blocking
                uploadThread?.join(TimeUnit.MINUTES.toMillis(5))

                // check uploader result
                uploadError.get()?.let { err ->
                    println("HttpProxy: Error during MinIO upload: ${err.message}")
                    doHttpReset(traceId, HEADER_STATUS_VALUE_500)
                    cleanupStreaming()
                    return
                }

                val objectName = claimKey!!
                println("HttpProxy: Upload successful for claimKey=$objectName, building response")

                val presignedUrl = if (resolved.presigned) {
                    println("HttpProxy: Generating presigned URL")
                    minioClient.getPresignedObjectUrl(
                        GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(options.bucket)
                            .`object`(objectName)
                            .expiry(resolved.ttl.toInt())
                            .build()
                    )
                } else null

                println("HttpProxy: Building HTTP response headers")
                val httpBeginEx = httpBeginExRW.wrap(extBuffer, 0, extBuffer.capacity())
                    .typeId(httpTypeId)
                    .headersItem { h -> h.name(HEADER_STATUS_NAME).value(HEADER_STATUS_VALUE_200) }
                    .headersItem { h -> h.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
                    .apply {
                        resolved.headers.forEach { (name, value) ->
                            val replaced = value.replace("uuid", objectName)
                            headersItem { h -> h.name(String8FW(name)).value(String16FW(replaced)) }
                            println("HttpProxy: Added header $name=$replaced")
                        }
                        if (resolved.headers.isEmpty() || !resolved.headers.containsKey("X-Claim")) {
                            val value = presignedUrl ?: objectName
                            headersItem { h -> h.name(HEADER_X_CLAIM_NAME).value(String16FW(value)) }
                            println("HttpProxy: Added X-Claim header with value=$value")
                        }
                    }
                    .build()

                println("HttpProxy: Sending HTTP Begin with traceId=$traceId")
                doHttpBegin(traceId, authorization, 0L, httpBeginEx)
                println("HttpProxy: Sending HTTP End with traceId=$traceId")
                doHttpEnd(traceId, authorization)

                cleanupStreaming()
                println("HttpProxy: Exiting onHttpEnd")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpEnd for initialId=$initialId: ${e.message}")
                e.printStackTrace()
                doHttpReset(end.traceId(), HEADER_STATUS_VALUE_500)
                cleanupStreaming()
            }
        }

        private fun onHttpAbort(abort: AbortFW) {
            println("HttpProxy: Entering onHttpAbort(initialId=$initialId)")
            try {
                val sequence = abort.sequence()
                val acknowledge = abort.acknowledge()
                val traceId = abort.traceId()
                val authorization = abort.authorization()

                initialSeq = sequence
                state = ClaimCheckState.closeInitial(state)
                println("HttpProxy: Aborting streaming upload")
                abortStreaming()
                doHttpAbort(traceId, authorization)
                println("HttpProxy: Exiting onHttpAbort")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpAbort for initialId=$initialId: ${e.message}")
                e.printStackTrace()
                doHttpReset(abort.traceId(), HEADER_STATUS_VALUE_500)
                cleanupStreaming()
            }
        }

        private fun onHttpReset(reset: ResetFW) {
            println("HttpProxy: Entering onHttpReset(initialId=$initialId)")
            try {
                val sequence = reset.sequence()
                val acknowledge = reset.acknowledge()
                val maximum = reset.maximum()
                val traceId = reset.traceId()

                replyAck = acknowledge
                replyMax = maximum
                state = ClaimCheckState.closeReply(state)
                println("HttpProxy: Reset received, aborting streaming upload if active")
                abortStreaming()
                println("HttpProxy: Exiting onHttpReset")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpReset for initialId=$initialId: ${e.message}")
                e.printStackTrace()
                doHttpReset(reset.traceId(), HEADER_STATUS_VALUE_500)
                cleanupStreaming()
            }
        }

        private fun onHttpWindow(window: WindowFW) {
            println("HttpProxy: Entering onHttpWindow(initialId=$initialId)")
            try {
                val sequence = window.sequence()
                val acknowledge = window.acknowledge()
                val maximum = window.maximum()
                val traceId = window.traceId()
                val authorization = window.authorization()
                val budgetId = window.budgetId()
                val padding = window.padding()
                val capabilities = window.capabilities()

                replyAck = acknowledge
                replyMax = maximum
                state = ClaimCheckState.openReply(state)
                println("HttpProxy: Window received: seq=$sequence, ack=$acknowledge, max=$maximum, budgetId=$budgetId, padding=$padding, capabilities=$capabilities")
                println("HttpProxy: Exiting onHttpWindow")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpWindow for initialId=$initialId: ${e.message}")
                e.printStackTrace()
                doHttpReset(window.traceId(), HEADER_STATUS_VALUE_500)
                cleanupStreaming()
            }
        }

        private fun onHttpFlush(flush: FlushFW) {
            println("HttpProxy: Entering onHttpFlush(initialId=$initialId)")
            try {
                val sequence = flush.sequence()
                val acknowledge = flush.acknowledge()
                val traceId = flush.traceId()
                val authorization = flush.authorization()
                val budgetId = flush.budgetId()
                val reserved = flush.reserved()

                replySeq = sequence
                lastActivityTime = System.currentTimeMillis()
                doHttpFlush(traceId, authorization, budgetId, reserved)
                println("HttpProxy: Exiting onHttpFlush")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpFlush for initialId=$initialId: ${e.message}")
                e.printStackTrace()
                doHttpReset(flush.traceId(), HEADER_STATUS_VALUE_500)
                cleanupStreaming()
            }
        }

        private fun doHttpBegin(traceId: Long, authorization: Long, affinity: Long, extension: Flyweight) {
            println("HttpProxy: Entering doHttpBegin(traceId=$traceId, initialId=$initialId)")
            try {
                state = ClaimCheckState.openingReply(state)
                doBegin(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, affinity, extension)
                println("HttpProxy: HTTP Begin sent with replyId=$replyId")
                println("HttpProxy: Exiting doHttpBegin")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpBegin for traceId=$traceId: ${e.message}")
                e.printStackTrace()
                doHttpReset(traceId, HEADER_STATUS_VALUE_500)
            }
        }

        private fun doHttpData(traceId: Long, authorization: Long, budgetId: Long, reserved: Int, flags: Int, payload: OctetsFW?) {
            println("HttpProxy: Entering doHttpData(traceId=$traceId, reserved=$reserved, initialId=$initialId)")
            try {
                doData(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, flags, reserved, payload)
                replySeq += reserved
                println("HttpProxy: HTTP Data sent, updated replySeq=$replySeq")
                println("HttpProxy: Exiting doHttpData")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpData for traceId=$traceId: ${e.message}")
                e.printStackTrace()
                doHttpReset(traceId, HEADER_STATUS_VALUE_500)
            }
        }

        private fun doHttpFlush(traceId: Long, authorization: Long, budgetId: Long, reserved: Int) {
            println("HttpProxy: Entering doHttpFlush(traceId=$traceId, initialId=$initialId)")
            try {
                doFlush(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, reserved)
                println("HttpProxy: HTTP Flush sent")
                println("HttpProxy: Exiting doHttpFlush")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpFlush for traceId=$traceId: ${e.message}")
                e.printStackTrace()
                doHttpReset(traceId, HEADER_STATUS_VALUE_500)
            }
        }

        private fun doHttpEnd(traceId: Long, authorization: Long) {
            println("HttpProxy: Entering doHttpEnd(traceId=$traceId, initialId=$initialId)")
            try {
                if (!ClaimCheckState.replyClosed(state)) {
                    state = ClaimCheckState.closeReply(state)
                    doEnd(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization)
                    println("HttpProxy: HTTP End sent with replyId=$replyId")
                }
                println("HttpProxy: Exiting doHttpEnd")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpEnd for traceId=$traceId: ${e.message}")
                e.printStackTrace()
                doHttpReset(traceId, HEADER_STATUS_VALUE_500)
            }
        }

        private fun doHttpAbort(traceId: Long, authorization: Long) {
            println("HttpProxy: Entering doHttpAbort(traceId=$traceId, initialId=$initialId)")
            try {
                if (!ClaimCheckState.replyClosed(state)) {
                    state = ClaimCheckState.closeReply(state)
                    doAbort(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization)
                    println("HttpProxy: HTTP Abort sent with replyId=$replyId")
                }
                println("HttpProxy: Exiting doHttpAbort")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpAbort for traceId=$traceId: ${e.message}")
                e.printStackTrace()
                doHttpReset(traceId, HEADER_STATUS_VALUE_500)
            }
        }

        private fun doHttpReset(traceId: Long, status: String16FW) {
            println("HttpProxy: Entering doHttpReset(traceId=$traceId, status=${status.asString()}, initialId=$initialId)")
            try {
                if (!ClaimCheckState.initialClosed(state)) {
                    state = ClaimCheckState.closeInitial(state)
                    val resetEx = httpResetExRW.wrap(extBuffer, 0, extBuffer.capacity())
                        .typeId(httpTypeId)
                        .headersItem { h -> h.name(HEADER_STATUS_NAME).value(status) }
                        .headersItem { h -> h.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
                        .build()
                    doReset(http, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, resetEx)
                    println("HttpProxy: HTTP Reset sent with status=${status.asString()}")
                }
                println("HttpProxy: Exiting doHttpReset")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpReset for traceId=$traceId: ${e.message}")
                e.printStackTrace()
            }
        }

        private fun cleanupStreaming() {
            println("HttpProxy: Entering cleanupStreaming(initialId=$initialId)")
            try {
                done.set(true)
                stream?.close()
                uploadThread?.join(TimeUnit.SECONDS.toMillis(5))
                queue?.clear()
                totalSize = 0
                expectedContentLength = null
                claimKey = null
                uploadStarted = false
                println("HttpProxy: Streaming resources cleaned up successfully")
            } catch (e: Exception) {
                println("HttpProxy: Error cleaning up streaming resources: ${e.message}")
                e.printStackTrace()
            } finally {
                queue = null
                stream = null
                uploadThread = null
                println("HttpProxy: Exiting cleanupStreaming")
            }
        }

        private fun abortStreaming() {
            println("HttpProxy: Entering abortStreaming(initialId=$initialId)")
            try {
                done.set(true)
                stream?.close()
                uploadThread?.join(TimeUnit.SECONDS.toMillis(5))
                queue?.clear()
                println("HttpProxy: Aborted streaming upload")
            } catch (e: Exception) {
                println("HttpProxy: Error aborting streaming: ${e.message}")
                e.printStackTrace()
            }
        }

        private class QueueInputStream(
            private val queue: LinkedBlockingQueue<ByteArray>,
            private val done: AtomicBoolean
        ) : InputStream() {
            private var current: ByteArray? = null
            private var offset = 0
            private var closed = false

            override fun read(): Int {
                val one = ByteArray(1)
                val r = read(one, 0, 1)
                return if (r == -1) -1 else (one[0].toInt() and 0xFF)
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (closed) return -1
                while (true) {
                    if (current == null || offset >= (current?.size ?: 0)) {
                        if (done.get() && queue.isEmpty()) {
                            return -1
                        }
                        current = queue.take()
                        offset = 0
                    }
                    val available = (current?.size ?: 0) - offset
                    if (available == 0) {
                        // loop to take next
                        continue
                    }
                    val toCopy = minOf(len, available)
                    System.arraycopy(current!!, offset, b, off, toCopy)
                    offset += toCopy
                    return toCopy
                }
            }

            fun finish() {
                // signal that producer is finished
                done.set(true)
            }

            override fun close() {
                closed = true
                done.set(true)
            }
        }
    }

    private fun doBegin(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        authorization: Long,
        affinity: Long,
        extension: Flyweight
    ) {
        println("ClaimCheckProxyFactory: Entering doBegin(traceId=$traceId, streamId=$streamId)")
        try {
            val begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .affinity(affinity)
                .extension(extension.buffer(), extension.offset(), extension.sizeof())
                .build()
            receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof())
            println("ClaimCheckProxyFactory: Begin sent successfully")
            println("ClaimCheckProxyFactory: Exiting doBegin")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doBegin for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun doData(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        authorization: Long,
        budgetId: Long,
        flags: Int,
        reserved: Int,
        payload: OctetsFW?
    ) {
        println("ClaimCheckProxyFactory: Entering doData(traceId=$traceId, streamId=$streamId, reserved=$reserved)")
        try {
            val data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .flags(flags)
                .budgetId(budgetId)
                .reserved(reserved)
                .payload(payload?.buffer(), payload?.offset() ?: 0, payload?.sizeof() ?: 0)
                .build()
            receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof())
            println("ClaimCheckProxyFactory: Data sent successfully")
            println("ClaimCheckProxyFactory: Exiting doData")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doData for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun doEnd(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        authorization: Long
    ) {
        println("ClaimCheckProxyFactory: Entering doEnd(traceId=$traceId, streamId=$streamId)")
        try {
            val end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .build()
            receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof())
            println("ClaimCheckProxyFactory: End sent successfully")
            println("ClaimCheckProxyFactory: Exiting doEnd")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doEnd for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun doAbort(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        authorization: Long
    ) {
        println("ClaimCheckProxyFactory: Entering doAbort(traceId=$traceId, streamId=$streamId)")
        try {
            val abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .build()
            receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof())
            println("ClaimCheckProxyFactory: Abort sent successfully")
            println("ClaimCheckProxyFactory: Exiting doAbort")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doAbort for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun doFlush(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        authorization: Long,
        budgetId: Long,
        reserved: Int
    ) {
        println("ClaimCheckProxyFactory: Entering doFlush(traceId=$traceId, streamId=$streamId)")
        try {
            val flush = flushRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .reserved(reserved)
                .build()
            receiver.accept(flush.typeId(), flush.buffer(), flush.offset(), flush.sizeof())
            println("ClaimCheckProxyFactory: Flush sent successfully")
            println("ClaimCheckProxyFactory: Exiting doFlush")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doFlush for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun doWindow(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        authorization: Long,
        budgetId: Long,
        padding: Int,
        capabilities: Int
    ) {
        println("ClaimCheckProxyFactory: Entering doWindow(traceId=$traceId, streamId=$streamId)")
        try {
            val window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .padding(padding)
                .capabilities(capabilities)
                .build()
            receiver.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof())
            println("ClaimCheckProxyFactory: Window sent successfully")
            println("ClaimCheckProxyFactory: Exiting doWindow")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doWindow for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun doReset(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        extension: HttpResetExFW?
    ) {
        println("ClaimCheckProxyFactory: Entering doReset(traceId=$traceId, streamId=$streamId)")
        try {
            val reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(streamId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .extension(extension?.buffer(), extension?.offset() ?: 0, extension?.sizeof() ?: 0)
                .build()
            receiver.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof())
            println("ClaimCheckProxyFactory: Reset sent successfully")
            println("ClaimCheckProxyFactory: Exiting doReset")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doReset for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun doHttpReset(
        receiver: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        status: String16FW
    ) {
        println("ClaimCheckProxyFactory: Entering doHttpReset(traceId=$traceId, streamId=$streamId, status=${status.asString()})")
        try {
            val resetEx = httpResetExRW.wrap(extBuffer, 0, extBuffer.capacity())
                .typeId(httpTypeId)
                .headersItem { h -> h.name(HEADER_STATUS_NAME).value(status) }
                .headersItem { h -> h.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
                .build()
            doReset(receiver, originId, routedId, streamId, sequence, acknowledge, maximum, traceId, resetEx)
            println("ClaimCheckProxyFactory: HTTP Reset sent with status=${status.asString()}")
            println("ClaimCheckProxyFactory: Exiting doHttpReset")
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in doHttpReset for traceId=$traceId: ${e.message}")
            e.printStackTrace()
        }
    }
}

object ClaimCheckState {
    private const val INITIAL_OPENED = 1 shl 0
    private const val INITIAL_CLOSED = 1 shl 1
    private const val REPLY_OPENED = 1 shl 2
    private const val REPLY_CLOSED = 1 shl 3

    fun openingInitial(state: Int): Int = state or INITIAL_OPENED
    fun openInitial(state: Int): Int = state or INITIAL_OPENED
    fun closeInitial(state: Int): Int = state or INITIAL_CLOSED
    fun openingReply(state: Int): Int = state or REPLY_OPENED
    fun openReply(state: Int): Int = state or REPLY_OPENED
    fun closeReply(state: Int): Int = state or REPLY_CLOSED
    fun initialOpened(state: Int): Boolean = (state and INITIAL_OPENED) != 0
    fun initialClosed(state: Int): Boolean = (state and INITIAL_CLOSED) != 0
    fun replyOpened(state: Int): Boolean = (state and REPLY_OPENED) != 0
    fun replyClosed(state: Int): Boolean = (state and REPLY_CLOSED) != 0
}
