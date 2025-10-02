package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream

import io.aklivity.zilla.runtime.binding.claimcheck.config.ClaimCheckWithConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckConfiguration
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckOptionsConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.Flyweight
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer
import io.aklivity.zilla.runtime.engine.config.BindingConfig
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.collections.Long2ObjectHashMap
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
import io.minio.GetPresignedObjectUrlArgs
import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.http.Method
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.function.LongUnaryOperator
import java.util.function.Predicate

class ClaimCheckProxyFactory(
    private val config: ClaimCheckConfiguration,
    private val context: EngineContext
) : BindingHandler {

    private companion object {
        const val HTTP_TYPE_NAME = "http"
        const val CLAIMCHECK_TYPE_NAME = "claimcheck"

        val HEADER_STATUS_NAME = String8FW(":status")
        val HEADER_METHOD_NAME = String8FW(":method")
        val HEADER_CONTENT_TYPE_NAME = String8FW("content-type")
        val HEADER_CONTENT_LENGTH_NAME = String8FW("content-length")
        val HEADER_X_CLAIM_NAME = String8FW("X-Claim")
        val HEADER_STATUS_VALUE_200 = String16FW("200")
        val HEADER_STATUS_VALUE_400 = String16FW("400")
        val HEADER_STATUS_VALUE_405 = String16FW("405")
        val HEADER_STATUS_VALUE_413 = String16FW("413")
        val HEADER_STATUS_VALUE_500 = String16FW("500")
        val EMPTY_EXTENSION = OctetsFW().wrap(UnsafeBuffer(ByteArray(0)), 0, 0)

        lateinit var SUPPORTED_HTTP_METHOD: Predicate<HttpHeaderFW>

        init {
            println("ClaimCheckProxyFactory: Initializing companion object")
            try {
                val headerMethodPost = HttpHeaderFW.Builder()
                    .wrap(UnsafeBuffer(ByteArray(512)), 0, 512)
                    .name(":method")
                    .value("POST")
                    .build()

                val headerMethodPut = HttpHeaderFW.Builder()
                    .wrap(UnsafeBuffer(ByteArray(512)), 0, 512)
                    .name(":method")
                    .value("PUT")
                    .build()

                SUPPORTED_HTTP_METHOD = Predicate<HttpHeaderFW> { header ->
                    headerMethodPost.equals(header) || headerMethodPut.equals(header)
                }
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
    private val claimCheckTypeId: Int = context.supplyTypeId(CLAIMCHECK_TYPE_NAME)
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
        println("ClaimCheckProxyFactory: Entering newStream(msgTypeId=$msgTypeId, index=$index, length=$length)")
        try {
            if (msgTypeId != BeginFW.TYPE_ID) {
                println("ClaimCheckProxyFactory: Invalid msgTypeId, expected BeginFW.TYPE_ID, returning null")
                return null
            }

            val begin = beginRO.wrap(buffer, index, index + length)
            val originId = begin.originId()
            val routedId = begin.routedId()
            val initialId = begin.streamId()
            val authorization = begin.authorization()
            val extension = begin.extension()
            println("ClaimCheckProxyFactory: Processing BeginFW: originId=$originId, routedId=$routedId, initialId=$initialId, authorization=$authorization")

            val beginEx = extension.get(httpBeginExRO::tryWrap)
            if (beginEx == null) {
                println("ClaimCheckProxyFactory: Failed to wrap HTTP extension, returning null")
                return null
            }

            println("ClaimCheckProxyFactory: Inspecting headers in beginEx")
            beginEx.headers().forEach { header ->
                println("ClaimCheckProxyFactory: Header: ${header.name().asString()}=${header.value().asString()}")
            }

            val binding = bindings[routedId]
            if (binding == null) {
                println("ClaimCheckProxyFactory: No binding found for routedId=$routedId, bindings available: ${bindings.keys}")
                return null
            }
            println("ClaimCheckProxyFactory: Found binding for routedId=$routedId, bindingId=${binding.id}")

            var methodValid = false
            beginEx.headers().forEach { header ->
                if (HEADER_METHOD_NAME.equals(header.name()) && SUPPORTED_HTTP_METHOD.test(header)) {
                    methodValid = true
                    println("ClaimCheckProxyFactory: Valid HTTP method found: ${header.value().asString()}")
                }
            }
            if (!methodValid) {
                println("ClaimCheckProxyFactory: Invalid HTTP method, sending 405 reset")
                doHttpReset(sender, originId, routedId, initialId, begin.sequence(), begin.acknowledge(), 0,
                    begin.traceId(), HEADER_STATUS_VALUE_405)
                return null
            }

            println("ClaimCheckProxyFactory: Attempting to resolve route with authorization=$authorization")
            val route = binding.resolve(authorization, beginEx)
            if (route == null) {
                println("ClaimCheckProxyFactory: No route resolved, returning null. Expected :path=/claim")
                return null
            }
            println("ClaimCheckProxyFactory: Route resolved, routeId=${route.id}")

            val resolved = route.with
            if (resolved == null) {
                println("ClaimCheckProxyFactory: No resolved config, returning null")
                return null
            }
            println("ClaimCheckProxyFactory: Resolved config found: ttl=${resolved.ttl}, maxPayloadSize=${resolved.maxPayloadSize}")

            println("ClaimCheckProxyFactory: Creating HttpProxy with routedId=$routedId")
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
            println("ClaimCheckProxyFactory: Exiting newStream, returning new HttpProxy stream")
            return result
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in newStream")
            e.printStackTrace()
            return null
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
        private val delegate = ClaimCheckProxy(originId, routedId, this, resolved, binding)
        private var state: Int = 0
        var initialSeq: Long = 0
        var initialAck: Long = 0
        var initialMax: Int = 0
        private var replySeq: Long = 0
        var replyAck: Long = 0
        var replyMax: Int = 0

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
                }
                println("HttpProxy: Exiting onHttpMessage for msgTypeId=$msgTypeId")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpMessage for msgTypeId=$msgTypeId")
                e.printStackTrace()
            }
        }

        private fun onHttpBegin(begin: BeginFW) {
            println("HttpProxy: Entering onHttpBegin(initialId=$initialId)")
            try {
                val sequence = begin.sequence()
                val acknowledge = begin.acknowledge()
                val traceId = begin.traceId()
                val authorization = begin.authorization()
                val affinity = begin.affinity()

                initialSeq = sequence
                initialAck = acknowledge
                state = ClaimCheckState.openingInitial(state)
                println("HttpProxy: Calling doClaimCheckBegin with traceId=$traceId")
                delegate.doClaimCheckBegin(traceId, authorization, affinity)
                println("HttpProxy: Exiting onHttpBegin")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpBegin for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun onHttpData(data: DataFW) {
            println("HttpProxy: Entering onHttpData(initialId=$initialId)")
            try {
                val sequence = data.sequence()
                val acknowledge = data.acknowledge()
                val traceId = data.traceId()
                val authorization = data.authorization()
                val budgetId = data.budgetId()
                val reserved = data.reserved()
                val flags = data.flags()
                val payload = data.payload()

                initialSeq = sequence
                println("HttpProxy: Calling doClaimCheckData with traceId=$traceId, reserved=$reserved")
                delegate.doClaimCheckData(traceId, authorization, budgetId, reserved, flags, payload)
                println("HttpProxy: Exiting onHttpData")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpData for initialId=$initialId")
                e.printStackTrace()
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
                println("HttpProxy: Calling doClaimCheckEnd with traceId=$traceId")
                delegate.doClaimCheckEnd(traceId, authorization)
                println("HttpProxy: Exiting onHttpEnd")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpEnd for initialId=$initialId")
                e.printStackTrace()
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
                println("HttpProxy: Calling doClaimCheckAbort with traceId=$traceId")
                delegate.doClaimCheckAbort(traceId, authorization)
                println("HttpProxy: Exiting onHttpAbort")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpAbort for initialId=$initialId")
                e.printStackTrace()
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
                println("HttpProxy: Calling doClaimCheckReset with traceId=$traceId")
                delegate.doClaimCheckReset(traceId)
                println("HttpProxy: Exiting onHttpReset")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpReset for initialId=$initialId")
                e.printStackTrace()
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
                println("HttpProxy: Calling doClaimCheckWindow with traceId=$traceId")
                delegate.doClaimCheckWindow(traceId, authorization, budgetId, padding, capabilities)
                println("HttpProxy: Exiting onHttpWindow")
            } catch (e: Exception) {
                println("HttpProxy: Error in onHttpWindow for initialId=$initialId")
                e.printStackTrace()
            }
        }

        fun doHttpBegin(traceId: Long, authorization: Long, affinity: Long, extension: Flyweight) {
            println("HttpProxy: Entering doHttpBegin(traceId=$traceId, initialId=$initialId)")
            try {
                replySeq = delegate.replySeq
                replyAck = delegate.replyAck
                replyMax = delegate.replyMax
                state = ClaimCheckState.openingReply(state)
                println("HttpProxy: Sending HTTP Begin with replyId=$replyId")
                doBegin(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, affinity, extension)
                println("HttpProxy: Exiting doHttpBegin")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpBegin for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doHttpData(traceId: Long, authorization: Long, budgetId: Long, reserved: Int, flags: Int, payload: OctetsFW?) {
            println("HttpProxy: Entering doHttpData(traceId=$traceId, reserved=$reserved, initialId=$initialId)")
            try {
                doData(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, flags, reserved, payload)
                replySeq += reserved
                println("HttpProxy: Exiting doHttpData, updated replySeq=$replySeq")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpData for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doHttpFlush(traceId: Long, authorization: Long, budgetId: Long, reserved: Int) {
            println("HttpProxy: Entering doHttpFlush(traceId=$traceId, initialId=$initialId)")
            try {
                replySeq = delegate.replySeq
                doFlush(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, reserved)
                println("HttpProxy: Exiting doHttpFlush")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpFlush for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doHttpEnd(traceId: Long, authorization: Long) {
            println("HttpProxy: Entering doHttpEnd(traceId=$traceId, initialId=$initialId)")
            try {
                if (!ClaimCheckState.replyClosed(state)) {
                    replySeq = delegate.replySeq
                    state = ClaimCheckState.closeReply(state)
                    doEnd(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization)
                    println("HttpProxy: HTTP End sent with replyId=$replyId")
                }
                println("HttpProxy: Exiting doHttpEnd")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpEnd for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doHttpAbort(traceId: Long, authorization: Long) {
            println("HttpProxy: Entering doHttpAbort(traceId=$traceId, initialId=$initialId)")
            try {
                if (!ClaimCheckState.replyClosed(state)) {
                    replySeq = delegate.replySeq
                    state = ClaimCheckState.closeReply(state)
                    doAbort(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization)
                    println("HttpProxy: HTTP Abort sent with replyId=$replyId")
                }
                println("HttpProxy: Exiting doHttpAbort")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpAbort for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doHttpReset(traceId: Long, status: String16FW) {
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
                println("HttpProxy: Error in doHttpReset for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doHttpWindow(authorization: Long, traceId: Long, budgetId: Long, padding: Int, capabilities: Int) {
            println("HttpProxy: Entering doHttpWindow(traceId=$traceId, initialId=$initialId)")
            try {
                initialAck = delegate.initialAck
                initialMax = delegate.initialMax
                doWindow(http, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization, budgetId, padding, capabilities)
                println("HttpProxy: HTTP Window sent with initialAck=$initialAck, initialMax=$initialMax")
                println("HttpProxy: Exiting doHttpWindow")
            } catch (e: Exception) {
                println("HttpProxy: Error in doHttpWindow for traceId=$traceId")
                e.printStackTrace()
            }
        }
    }

    private inner class ClaimCheckProxy(
        private val originId: Long,
        private val routedId: Long,
        private val delegate: HttpProxy,
        private val resolved: ClaimCheckWithConfig,
        private val binding: ClaimCheckBindingConfig
    ) {
        private val options: ClaimCheckOptionsConfig
        private val initialId: Long = supplyInitialId.applyAsLong(routedId)
        private val replyId: Long = supplyReplyId.applyAsLong(initialId)
        private var filesystem: MessageConsumer? = null
        private var state: Int = 0
        private var initialSeq: Long = 0
        var initialAck: Long = 0
        var initialMax: Int = 0
        var replySeq: Long = 0
        var replyAck: Long = 0
        var replyMax: Int = 0
        private val chunks = mutableListOf<ByteArray>()
        private var totalSize: Long = 0
        private var tempFile: File? = null
        private var tempFileOutputStream: FileOutputStream? = null
        private val minioClient: MinioClient

        init {
            println("ClaimCheckProxy: Entering constructor for initialId=$initialId, routedId=$routedId")
            try {
                options = binding.options
                println("ClaimCheckProxy: Binding options: $options")
                minioClient = MinioClient.builder()
                    .endpoint(options.endpoint)
                    .credentials(options.accessKey, options.secretKey)
                    .build()
                println("ClaimCheckProxy: MinioClient initialized successfully")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error initializing ClaimCheckProxy for initialId=$initialId")
                e.printStackTrace()
                throw e
            }
            println("ClaimCheckProxy: Exiting constructor")
        }
        fun doClaimCheckBegin(traceId: Long, authorization: Long, affinity: Long) {
            println("ClaimCheckProxy: Entering doClaimCheckBegin(traceId=$traceId, initialId=$initialId)")
            try {
                initialSeq = delegate.initialSeq
                initialAck = delegate.initialAck
                initialMax = delegate.initialMax
                state = ClaimCheckState.openingInitial(state)
                filesystem = newClaimCheckStream(this::onClaimCheckMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization, affinity)
                println("ClaimCheckProxy: Filesystem stream created, calling doClaimCheckWindow")
                doClaimCheckWindow(traceId, authorization, 0L, resolved.inMemoryThreshold.toInt(), 0)
                println("ClaimCheckProxy: Exiting doClaimCheckBegin")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in doClaimCheckBegin for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doClaimCheckData(traceId: Long, authorization: Long, budgetId: Long, reserved: Int, flags: Int, payload: OctetsFW) {
            println("ClaimCheckProxy: Entering doClaimCheckData(traceId=$traceId, reserved=$reserved, initialId=$initialId)")
            try {
                val size = payload.sizeof()
                totalSize += size
                println("ClaimCheckProxy: Payload size=$size, totalSize=$totalSize")
                if (totalSize > resolved.maxPayloadSize) {
                    println("ClaimCheckProxy: Payload size exceeds maxPayloadSize=${resolved.maxPayloadSize}, sending 413")
                    delegate.doHttpReset(traceId, HEADER_STATUS_VALUE_413)
                    doClaimCheckAbort(traceId, authorization)
                    return
                }
                if (totalSize <= resolved.inMemoryThreshold && tempFile == null) {
                    val bytes = ByteArray(size)
                    payload.buffer().getBytes(payload.offset(), bytes)
                    chunks.add(bytes)
                    println("ClaimCheckProxy: Stored payload in memory, chunk count=${chunks.size}")
                } else {
                    if (tempFile == null) {
                        println("ClaimCheckProxy: Creating temp file as totalSize exceeds inMemoryThreshold")
                        tempFile = File.createTempFile("claimcheck", ".tmp")
                        tempFileOutputStream = FileOutputStream(tempFile!!)
                        chunks.forEach { tempFileOutputStream!!.write(it) }
                        chunks.clear()
                        println("ClaimCheckProxy: Moved chunks to temp file")
                    }
                    val bytes = ByteArray(size)
                    payload.buffer().getBytes(payload.offset(), bytes, 0, size)
                    tempFileOutputStream!!.write(bytes)
                    println("ClaimCheckProxy: Wrote payload to temp file")
                }
                doData(filesystem!!, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization, budgetId, flags, reserved, payload)
                initialSeq += reserved
                println("ClaimCheckProxy: Sent data, updated initialSeq=$initialSeq")
                doClaimCheckWindow(traceId, authorization, budgetId, reserved, 0)
                println("ClaimCheckProxy: Exiting doClaimCheckData")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in doClaimCheckData for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doClaimCheckEnd(traceId: Long, authorization: Long) {
            println("ClaimCheckProxy: Entering doClaimCheckEnd(traceId=$traceId, initialId=$initialId)")
            try {
                if (!ClaimCheckState.initialClosed(state)) {
                    initialSeq = delegate.initialSeq
                    state = ClaimCheckState.closeInitial(state)
                    val claimKey = UUID.randomUUID().toString()
                    println("ClaimCheckProxy: Generated claimKey=$claimKey")
                    try {
                        val inputStream = if (tempFile != null) {
                            println("ClaimCheckProxy: Closing temp file output stream")
                            tempFileOutputStream?.close()
                            tempFile!!.inputStream()
                        } else {
                            ByteArrayInputStream(chunks.concatToByteArray())
                        }
                        println("ClaimCheckProxy: Uploading to MinIO with claimKey=$claimKey")
                        minioClient.putObject(
                            PutObjectArgs.builder()
                                .bucket(options.bucket)
                                .`object`(claimKey)
                                .stream(inputStream, totalSize, -1)
                                .build()
                        )
                        println("ClaimCheckProxy: MinIO upload successful")
                        val presignedUrl = if (resolved.presigned) {
                            println("ClaimCheckProxy: Generating presigned URL")
                            minioClient.getPresignedObjectUrl(
                                GetPresignedObjectUrlArgs.builder()
                                    .method(Method.GET)
                                    .bucket(options.bucket)
                                    .`object`(claimKey)
                                    .expiry(resolved.ttl.toInt())
                                    .build()
                            )
                        } else null
                        println("ClaimCheckProxy: Building HTTP response headers")
                        val httpBeginEx = httpBeginExRW.wrap(extBuffer, 0, extBuffer.capacity())
                            .typeId(httpTypeId)
                            .headersItem { h -> h.name(HEADER_STATUS_NAME).value(HEADER_STATUS_VALUE_200) }
                            .headersItem { h -> h.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
                            .apply {
                                resolved.headers.forEach { (name, value) ->
                                    val replaced = value.replace("uuid", claimKey)
                                    headersItem { h -> h.name(String8FW(name)).value(String16FW(replaced)) }
                                    println("ClaimCheckProxy: Added header $name=$replaced")
                                }
                                if (resolved.headers.isEmpty() || !resolved.headers.containsKey("X-Claim")) {
                                    val value = presignedUrl ?: claimKey
                                    headersItem { h -> h.name(HEADER_X_CLAIM_NAME).value(String16FW(value)) }
                                    println("ClaimCheckProxy: Added X-Claim header with value=$value")
                                }
                            }
                            .build()
                        println("ClaimCheckProxy: Sending HTTP Begin with traceId=$traceId")
                        delegate.doHttpBegin(traceId, authorization, 0L, httpBeginEx)
                        println("ClaimCheckProxy: Sending HTTP End with traceId=$traceId")
                        delegate.doHttpEnd(traceId, authorization)
                    } catch (ex: Exception) {
                        println("ClaimCheckProxy: Error during MinIO operations or HTTP response")
                        ex.printStackTrace()
                        delegate.doHttpReset(traceId, HEADER_STATUS_VALUE_500)
                    } finally {
                        println("ClaimCheckProxy: Cleaning up temp file")
                        cleanupTempFile()
                    }
                    println("ClaimCheckProxy: Sending ClaimCheck End with initialId=$initialId")
                    doEnd(filesystem!!, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization)
                }
                println("ClaimCheckProxy: Exiting doClaimCheckEnd")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in doClaimCheckEnd for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doClaimCheckAbort(traceId: Long, authorization: Long) {
            println("ClaimCheckProxy: Entering doClaimCheckAbort(traceId=$traceId, initialId=$initialId)")
            try {
                if (!ClaimCheckState.initialClosed(state)) {
                    initialSeq = delegate.initialSeq
                    state = ClaimCheckState.closeInitial(state)
                    println("ClaimCheckProxy: Cleaning up temp file in abort")
                    cleanupTempFile()
                    doAbort(filesystem!!, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization)
                    println("ClaimCheckProxy: ClaimCheck Abort sent")
                }
                println("ClaimCheckProxy: Exiting doClaimCheckAbort")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in doClaimCheckAbort for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doClaimCheckReset(traceId: Long) {
            println("ClaimCheckProxy: Entering doClaimCheckReset(traceId=$traceId, initialId=$initialId)")
            try {
                if (!ClaimCheckState.replyClosed(state)) {
                    state = ClaimCheckState.closeReply(state)
                    println("ClaimCheckProxy: Cleaning up temp file in reset")
                    cleanupTempFile()
                    doReset(filesystem!!, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, EMPTY_EXTENSION as HttpResetExFW?)
                    println("ClaimCheckProxy: ClaimCheck Reset sent")
                }
                println("ClaimCheckProxy: Exiting doClaimCheckReset")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in doClaimCheckReset for traceId=$traceId")
                e.printStackTrace()
            }
        }

        fun doClaimCheckWindow(traceId: Long, authorization: Long, budgetId: Long, padding: Int, capabilities: Int) {
            println("ClaimCheckProxy: Entering doClaimCheckWindow(traceId=$traceId, initialId=$initialId)")
            try {
                replyAck = delegate.replyAck
                replyMax = delegate.replyMax
                doWindow(filesystem!!, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, padding, capabilities)
                println("ClaimCheckProxy: ClaimCheck Window sent with replyAck=$replyAck, replyMax=$replyMax")
                println("ClaimCheckProxy: Exiting doClaimCheckWindow")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in doClaimCheckWindow for traceId=$traceId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckMessage(
            msgTypeId: Int,
            buffer: DirectBuffer,
            index: Int,
            length: Int
        ) {
            println("ClaimCheckProxy: Entering onClaimCheckMessage(msgTypeId=$msgTypeId, initialId=$initialId)")
            try {
                when (msgTypeId) {
                    BeginFW.TYPE_ID -> {
                        val begin = beginRO.wrap(buffer, index, index + length)
                        onClaimCheckBegin(begin)
                    }
                    DataFW.TYPE_ID -> {
                        val data = dataRO.wrap(buffer, index, index + length)
                        onClaimCheckData(data)
                    }
                    EndFW.TYPE_ID -> {
                        val end = endRO.wrap(buffer, index, index + length)
                        onClaimCheckEnd(end)
                    }
                    AbortFW.TYPE_ID -> {
                        val abort = abortRO.wrap(buffer, index, index + length)
                        onClaimCheckAbort(abort)
                    }
                    FlushFW.TYPE_ID -> {
                        val flush = flushRO.wrap(buffer, index, index + length)
                        onClaimCheckFlush(flush)
                    }
                    WindowFW.TYPE_ID -> {
                        val window = windowRO.wrap(buffer, index, index + length)
                        onClaimCheckWindow(window)
                    }
                    ResetFW.TYPE_ID -> {
                        val reset = resetRO.wrap(buffer, index, index + length)
                        onClaimCheckReset(reset)
                    }
                }
                println("ClaimCheckProxy: Exiting onClaimCheckMessage for msgTypeId=$msgTypeId")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckMessage for msgTypeId=$msgTypeId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckBegin(begin: BeginFW) {
            println("ClaimCheckProxy: Entering onClaimCheckBegin(initialId=$initialId)")
            try {
                val sequence = begin.sequence()
                val acknowledge = begin.acknowledge()
                val traceId = begin.traceId()
                val authorization = begin.authorization()
                val affinity = begin.affinity()

                replySeq = sequence
                replyAck = acknowledge
                state = ClaimCheckState.openingReply(state)
                println("ClaimCheckProxy: Sending HTTP Begin with traceId=$traceId")
                delegate.doHttpBegin(traceId, authorization, affinity, EMPTY_EXTENSION)
                println("ClaimCheckProxy: Exiting onClaimCheckBegin")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckBegin for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckData(data: DataFW) {
            println("ClaimCheckProxy: Entering onClaimCheckData(initialId=$initialId)")
            try {
                val sequence = data.sequence()
                val acknowledge = data.acknowledge()
                val traceId = data.traceId()
                val authorization = data.authorization()
                val budgetId = data.budgetId()
                val reserved = data.reserved()
                val flags = data.flags()
                val payload = data.payload()

                replySeq = sequence + reserved
                if (replySeq > replyAck + replyMax) {
                    println("ClaimCheckProxy: Reply sequence exceeded, sending reset")
                    doClaimCheckReset(traceId)
                    delegate.doHttpAbort(traceId, authorization)
                } else {
                    println("ClaimCheckProxy: Sending HTTP Data with traceId=$traceId, reserved=$reserved")
                    delegate.doHttpData(traceId, authorization, budgetId, reserved, flags, payload)
                }
                println("ClaimCheckProxy: Exiting onClaimCheckData")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckData for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckEnd(end: EndFW) {
            println("ClaimCheckProxy: Entering onClaimCheckEnd(initialId=$initialId)")
            try {
                val sequence = end.sequence()
                val acknowledge = end.acknowledge()
                val traceId = end.traceId()
                val authorization = end.authorization()

                replySeq = sequence
                state = ClaimCheckState.closeReply(state)
                println("ClaimCheckProxy: Sending HTTP End with traceId=$traceId")
                delegate.doHttpEnd(traceId, authorization)
                println("ClaimCheckProxy: Exiting onClaimCheckEnd")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckEnd for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckFlush(flush: FlushFW) {
            println("ClaimCheckProxy: Entering onClaimCheckFlush(initialId=$initialId)")
            try {
                val sequence = flush.sequence()
                val acknowledge = flush.acknowledge()
                val traceId = flush.traceId()
                val authorization = flush.authorization()
                val budgetId = flush.budgetId()
                val reserved = flush.reserved()

                replySeq = sequence
                println("ClaimCheckProxy: Sending HTTP Flush with traceId=$traceId")
                delegate.doHttpFlush(traceId, authorization, budgetId, reserved)
                println("ClaimCheckProxy: Exiting onClaimCheckFlush")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckFlush for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckAbort(abort: AbortFW) {
            println("ClaimCheckProxy: Entering onClaimCheckAbort(initialId=$initialId)")
            try {
                val sequence = abort.sequence()
                val acknowledge = abort.acknowledge()
                val traceId = abort.traceId()
                val authorization = abort.authorization()

                replySeq = sequence
                state = ClaimCheckState.closeReply(state)
                println("ClaimCheckProxy: Sending HTTP Abort with traceId=$traceId")
                delegate.doHttpAbort(traceId, authorization)
                println("ClaimCheckProxy: Exiting onClaimCheckAbort")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckAbort for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckWindow(window: WindowFW) {
            println("ClaimCheckProxy: Entering onClaimCheckWindow(initialId=$initialId)")
            try {
                val sequence = window.sequence()
                val acknowledge = window.acknowledge()
                val maximum = window.maximum()
                val traceId = window.traceId()
                val authorization = window.authorization()
                val budgetId = window.budgetId()
                val padding = window.padding()
                val capabilities = window.capabilities()

                initialAck = acknowledge
                initialMax = maximum
                state = ClaimCheckState.openInitial(state)
                println("ClaimCheckProxy: Sending HTTP Window with traceId=$traceId")
                delegate.doHttpWindow(authorization, traceId, budgetId, padding, capabilities)
                println("ClaimCheckProxy: Exiting onClaimCheckWindow")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckWindow for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun onClaimCheckReset(reset: ResetFW) {
            println("ClaimCheckProxy: Entering onClaimCheckReset(initialId=$initialId)")
            try {
                val traceId = reset.traceId()
                println("ClaimCheckProxy: Sending HTTP Reset with status 400, traceId=$traceId")
                delegate.doHttpReset(traceId, HEADER_STATUS_VALUE_400)
                println("ClaimCheckProxy: Exiting onClaimCheckReset")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in onClaimCheckReset for initialId=$initialId")
                e.printStackTrace()
            }
        }

        private fun cleanupTempFile() {
            println("ClaimCheckProxy: Entering cleanupTempFile(initialId=$initialId)")
            try {
                tempFileOutputStream?.close()
                tempFile?.let { if (it.exists()) it.delete() }
                println("ClaimCheckProxy: Temp file cleaned up successfully")
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error cleaning up temp file")
                e.printStackTrace()
            } finally {
                tempFile = null
                tempFileOutputStream = null
                println("ClaimCheckProxy: Exiting cleanupTempFile")
            }
        }

        private fun List<ByteArray>.concatToByteArray(): ByteArray {
            println("ClaimCheckProxy: Entering concatToByteArray(initialId=$initialId)")
            try {
                val total = sumOf { it.size }
                val out = ByteArray(total)
                var off = 0
                for (b in this) {
                    System.arraycopy(b, 0, out, off, b.size)
                    off += b.size
                }
                println("ClaimCheckProxy: Exiting concatToByteArray, created array of size=$total")
                return out
            } catch (e: Exception) {
                println("ClaimCheckProxy: Error in concatToByteArray")
                e.printStackTrace()
                throw e
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
            println("ClaimCheckProxyFactory: Error in doBegin for traceId=$traceId")
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
            println("ClaimCheckProxyFactory: Error in doData for traceId=$traceId")
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
            println("ClaimCheckProxyFactory: Error in doEnd for traceId=$traceId")
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
            println("ClaimCheckProxyFactory: Error in doAbort for traceId=$traceId")
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
            println("ClaimCheckProxyFactory: Error in doFlush for traceId=$traceId")
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
            println("ClaimCheckProxyFactory: Error in doWindow for traceId=$traceId")
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
            println("ClaimCheckProxyFactory: Error in doReset for traceId=$traceId")
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
            println("ClaimCheckProxyFactory: Error in doHttpReset for traceId=$traceId")
            e.printStackTrace()
        }
    }

    private fun newClaimCheckStream(
        sender: MessageConsumer,
        originId: Long,
        routedId: Long,
        streamId: Long,
        sequence: Long,
        acknowledge: Long,
        maximum: Int,
        traceId: Long,
        authorization: Long,
        affinity: Long
    ): MessageConsumer {
        println("ClaimCheckProxyFactory: Entering newClaimCheckStream(traceId=$traceId, streamId=$streamId)")
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
                .extension(EMPTY_EXTENSION.buffer(), EMPTY_EXTENSION.offset(), EMPTY_EXTENSION.sizeof())
                .build()
            val receiver = streamFactory.newStream(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof(), sender)
            receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof())
            println("ClaimCheckProxyFactory: ClaimCheck stream created and Begin sent")
            println("ClaimCheckProxyFactory: Exiting newClaimCheckStream")
            return receiver
        } catch (e: Exception) {
            println("ClaimCheckProxyFactory: Error in newClaimCheckStream for traceId=$traceId")
            e.printStackTrace()
            throw e
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