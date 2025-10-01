package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream

import io.aklivity.zilla.runtime.binding.claimcheck.config.ClaimCheckWithConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckConfiguration
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckOptionsConfig
import io.aklivity.zilla.runtime.binding.http.internal.types.Flyweight
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer
import io.aklivity.zilla.runtime.engine.config.BindingConfig
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.collections.Long2ObjectHashMap
import io.aklivity.zilla.runtime.binding.http.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.binding.http.internal.types.OctetsFW
import io.aklivity.zilla.runtime.binding.http.internal.types.String8FW
import io.aklivity.zilla.runtime.binding.http.internal.types.String16FW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.AbortFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.BeginFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.DataFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.EndFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.FlushFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.HttpBeginExFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.HttpResetExFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.ResetFW
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.WindowFW
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

        val SUPPORTED_HTTP_METHOD: Predicate<HttpHeaderFW>

        init {
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
        }
    }

    private val writeBuffer: MutableDirectBuffer = context.writeBuffer()
    private val extBuffer: MutableDirectBuffer = UnsafeBuffer(ByteArray(context.writeBuffer().capacity()))
    private val streamFactory: BindingHandler = context.streamFactory()
    private val supplyInitialId: LongUnaryOperator =LongUnaryOperator { value -> context.supplyInitialId(value) }
    private val supplyReplyId: LongUnaryOperator = LongUnaryOperator { value -> context.supplyReplyId(value) }
    private val httpTypeId: Int = context.supplyTypeId(HTTP_TYPE_NAME)
    private val claimCheckTypeId: Int = context.supplyTypeId(CLAIMCHECK_TYPE_NAME)
    private val bindings = Long2ObjectHashMap<ClaimCheckBindingConfig>()
    private val options: ClaimCheckOptionsConfig = bindings.get(routedTypeId().toLong()).options

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
        bindings[binding.id] = ClaimCheckBindingConfig(binding)
    }

     fun detach(bindingId: Long) {
        bindings.remove(bindingId)
    }


    override fun newStream(
        msgTypeId: Int,
        buffer: DirectBuffer,
        index: Int,
        length: Int,
        sender: MessageConsumer
    ): MessageConsumer? {
        if (msgTypeId != BeginFW.TYPE_ID) return null

        val begin = beginRO.wrap(buffer, index, index + length)
        val originId = begin.originId()
        val routedId = begin.routedId()
        val initialId = begin.streamId()
        val authorization = begin.authorization()
        val extension = begin.extension()
        val beginEx = extension.get(httpBeginExRO::tryWrap) ?: return null
        val binding = bindings[routedId] ?: return null

        // Validate HTTP method
        var methodValid = false
        beginEx.headers().forEach { header ->
            if (HEADER_METHOD_NAME.equals(header.name()) && SUPPORTED_HTTP_METHOD.test(header)) {
                methodValid = true
            }
        }
        if (!methodValid) {
            doHttpReset(sender, originId, routedId, initialId, begin.sequence(), begin.acknowledge(), 0,
                begin.traceId(), HEADER_STATUS_VALUE_405)
            return null
        }

        val route = binding.resolve(authorization, beginEx) ?: return null
        val resolved = route.with ?: return null

        return HttpProxy(
            http = sender,
            originId = originId,
            routedId = routedId,
            initialId = initialId,
            resolvedId = route.id,
            resolved = resolved
        ).newStream()
    }

    private inner class HttpProxy(
        private val http: MessageConsumer,
        private val originId: Long,
        private val routedId: Long,
        private val initialId: Long,
        private val resolvedId: Long,
        private val resolved: ClaimCheckWithConfig
    ) {
        private val replyId: Long = supplyReplyId.applyAsLong(initialId)
        private val delegate = ClaimCheckProxy(routedId, resolvedId, this, resolved)
        private var state: Int = 0
        var initialSeq: Long = 0
        var initialAck: Long = 0
        var initialMax: Int = 0
        private var replySeq: Long = 0
        var replyAck: Long = 0
        var replyMax: Int = 0

        fun newStream(): MessageConsumer = MessageConsumer { t, b, i, l ->
            onHttpMessage(t, b, i, l)
        }

        private fun onHttpMessage(
            msgTypeId: Int,
            buffer: DirectBuffer,
            index: Int,
            length: Int
        ) {
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
        }

        private fun onHttpBegin(begin: BeginFW) {
            val sequence = begin.sequence()
            val acknowledge = begin.acknowledge()
            val traceId = begin.traceId()
            val authorization = begin.authorization()
            val affinity = begin.affinity()

            initialSeq = sequence
            initialAck = acknowledge
            state = ClaimCheckState.openingInitial(state)
            delegate.doClaimCheckBegin(traceId, authorization, affinity)
        }

        private fun onHttpData(data: DataFW) {
            val sequence = data.sequence()
            val acknowledge = data.acknowledge()
            val traceId = data.traceId()
            val authorization = data.authorization()
            val budgetId = data.budgetId()
            val reserved = data.reserved()
            val flags = data.flags()
            val payload = data.payload()

            initialSeq = sequence
            delegate.doClaimCheckData(traceId, authorization, budgetId, reserved, flags, payload)
        }

        private fun onHttpEnd(end: EndFW) {
            val sequence = end.sequence()
            val acknowledge = end.acknowledge()
            val traceId = end.traceId()
            val authorization = end.authorization()

            initialSeq = sequence
            state = ClaimCheckState.closeInitial(state)
            delegate.doClaimCheckEnd(traceId, authorization)
        }

        private fun onHttpAbort(abort: AbortFW) {
            val sequence = abort.sequence()
            val acknowledge = abort.acknowledge()
            val traceId = abort.traceId()
            val authorization = abort.authorization()

            initialSeq = sequence
            state = ClaimCheckState.closeInitial(state)
            delegate.doClaimCheckAbort(traceId, authorization)
        }

        private fun onHttpReset(reset: ResetFW) {
            val sequence = reset.sequence()
            val acknowledge = reset.acknowledge()
            val maximum = reset.maximum()
            val traceId = reset.traceId()

            replyAck = acknowledge
            replyMax = maximum
            state = ClaimCheckState.closeReply(state)
            delegate.doClaimCheckReset(traceId)
        }

        private fun onHttpWindow(window: WindowFW) {
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
            delegate.doClaimCheckWindow(traceId, authorization, budgetId, padding, capabilities)
        }

        fun doHttpBegin(traceId: Long, authorization: Long, affinity: Long, extension: Flyweight) {
            replySeq = delegate.replySeq
            replyAck = delegate.replyAck
            replyMax = delegate.replyMax
            state = ClaimCheckState.openingReply(state)
            doBegin(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, affinity, extension)
        }

        fun doHttpData(traceId: Long, authorization: Long, budgetId: Long, reserved: Int, flags: Int, payload: Flyweight) {
            doData(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, flags, reserved, payload)
            replySeq += reserved
        }

        fun doHttpFlush(traceId: Long, authorization: Long, budgetId: Long, reserved: Int) {
            replySeq = delegate.replySeq
            doFlush(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, reserved)
        }

        fun doHttpEnd(traceId: Long, authorization: Long) {
            if (!ClaimCheckState.replyClosed(state)) {
                replySeq = delegate.replySeq
                state = ClaimCheckState.closeReply(state)
                doEnd(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization)
            }
        }

        fun doHttpAbort(traceId: Long, authorization: Long) {
            if (!ClaimCheckState.replyClosed(state)) {
                replySeq = delegate.replySeq
                state = ClaimCheckState.closeReply(state)
                doAbort(http, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization)
            }
        }

        fun doHttpReset(traceId: Long, status: String16FW) {
            if (!ClaimCheckState.initialClosed(state)) {
                state = ClaimCheckState.closeInitial(state)
                val resetEx = httpResetExRW.wrap(extBuffer, 0, extBuffer.capacity())
                    .typeId(httpTypeId)
                    .headersItem { h -> h.name(HEADER_STATUS_NAME).value(status) }
                    .headersItem { h -> h.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
                    .build()
                doReset(http, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, resetEx)
            }
        }

        fun doHttpWindow(authorization: Long, traceId: Long, budgetId: Long, padding: Int, capabilities: Int) {
            initialAck = delegate.initialAck
            initialMax = delegate.initialMax
            doWindow(http, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization, budgetId, padding, capabilities)
        }
    }

    private inner class ClaimCheckProxy(
        private val originId: Long,
        private val routedId: Long,
        private val delegate: HttpProxy,
        private val resolved: ClaimCheckWithConfig
    ) {
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
        private val minioClient: MinioClient = MinioClient.builder()
            .endpoint(options.endpoint)
            .credentials(options.accessKey, options.secretKey)
            .build()

        fun doClaimCheckBegin(traceId: Long, authorization: Long, affinity: Long) {
            initialSeq = delegate.initialSeq
            initialAck = delegate.initialAck
            initialMax = delegate.initialMax
            state = ClaimCheckState.openingInitial(state)
            filesystem = newClaimCheckStream(this::onClaimCheckMessage, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization, affinity)
            doClaimCheckWindow(traceId, authorization, 0L, resolved.inMemoryThreshold.toInt(), 0)
        }

        fun doClaimCheckData(traceId: Long, authorization: Long, budgetId: Long, reserved: Int, flags: Int, payload: Flyweight) {
            val size = payload.sizeof()
            totalSize += size
            if (totalSize > resolved.maxPayloadSize) {
                delegate.doHttpReset(traceId, HEADER_STATUS_VALUE_413)
                doClaimCheckAbort(traceId, authorization)
                return
            }
            if (totalSize <= resolved.inMemoryThreshold && tempFile == null) {
                val bytes = ByteArray(size)
                payload.buffer().getBytes(payload.offset(), bytes)
                chunks.add(bytes)
            } else {
                if (tempFile == null) {
                    tempFile = File.createTempFile("claimcheck", ".tmp")
                    tempFileOutputStream = FileOutputStream(tempFile!!)
                    chunks.forEach { tempFileOutputStream!!.write(it) }
                    chunks.clear()
                }
                val bytes = ByteArray(size)
                payload.buffer().getBytes(payload.offset(), bytes, 0, size)
                tempFileOutputStream!!.write(bytes)
            }
            doData(filesystem!!, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization, budgetId, flags, reserved, payload)
            initialSeq += reserved
            doClaimCheckWindow(traceId, authorization, budgetId, reserved, 0)
        }

        fun doClaimCheckEnd(traceId: Long, authorization: Long) {
            if (!ClaimCheckState.initialClosed(state)) {
                initialSeq = delegate.initialSeq
                state = ClaimCheckState.closeInitial(state)
                val claimKey = UUID.randomUUID().toString()
                try {
                    val inputStream = if (tempFile != null) {
                        tempFileOutputStream?.close()
                        tempFile!!.inputStream()
                    } else {
                        ByteArrayInputStream(chunks.concatToByteArray())
                    }
                    minioClient.putObject(
                        PutObjectArgs.builder()
                            .bucket(options.bucket)
                            .`object`(claimKey)
                            .stream(inputStream, totalSize, -1)
                            .build()
                    )
                    val presignedUrl = if (resolved.presigned) {
                        minioClient.getPresignedObjectUrl(
                            GetPresignedObjectUrlArgs.builder()
                                .method(Method.GET)
                                .bucket(options.bucket)
                                .`object`(claimKey)
                                .expiry(resolved.ttl.toInt())
                                .build()
                        )
                    } else null
                    val httpBeginEx = httpBeginExRW.wrap(extBuffer, 0, extBuffer.capacity())
                        .typeId(httpTypeId)
                        .headersItem { h -> h.name(HEADER_STATUS_NAME).value(HEADER_STATUS_VALUE_200) }
                        .headersItem { h -> h.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
                        .apply {
                            resolved.headers.forEach { (name, value) ->
                                val replaced = value.replace("uuid", claimKey)
                                headersItem { h -> h.name(String8FW(name)).value(String16FW(replaced)) }
                            }
                            if (resolved.headers.isEmpty() || !resolved.headers.containsKey("X-Claim")) {
                                val value = presignedUrl ?: claimKey
                                headersItem { h -> h.name(HEADER_X_CLAIM_NAME).value(String16FW(value)) }
                            }
                        }
                        .build()
                    delegate.doHttpBegin(traceId, authorization, 0L, httpBeginEx)
                    delegate.doHttpEnd(traceId, authorization)
                } catch (ex: Exception) {
                    delegate.doHttpReset(traceId, HEADER_STATUS_VALUE_500)
                } finally {
                    cleanupTempFile()
                }
                doEnd(filesystem!!, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization)
            }
        }

        fun doClaimCheckAbort(traceId: Long, authorization: Long) {
            if (!ClaimCheckState.initialClosed(state)) {
                initialSeq = delegate.initialSeq
                state = ClaimCheckState.closeInitial(state)
                cleanupTempFile()
                doAbort(filesystem!!, originId, routedId, initialId, initialSeq, initialAck, initialMax, traceId, authorization)
            }
        }

        fun doClaimCheckReset(traceId: Long) {
            if (!ClaimCheckState.replyClosed(state)) {
                state = ClaimCheckState.closeReply(state)
                cleanupTempFile()
                doReset(filesystem!!, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, EMPTY_EXTENSION)
            }
        }

        fun doClaimCheckWindow(traceId: Long, authorization: Long, budgetId: Long, padding: Int, capabilities: Int) {
            replyAck = delegate.replyAck
            replyMax = delegate.replyMax
            doWindow(filesystem!!, originId, routedId, replyId, replySeq, replyAck, replyMax, traceId, authorization, budgetId, padding, capabilities)
        }

        private fun onClaimCheckMessage(
            msgTypeId: Int,
            buffer: DirectBuffer,
            index: Int,
            length: Int
        ) {
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
        }

        private fun onClaimCheckBegin(begin: BeginFW) {
            val sequence = begin.sequence()
            val acknowledge = begin.acknowledge()
            val traceId = begin.traceId()
            val authorization = begin.authorization()
            val affinity = begin.affinity()

            replySeq = sequence
            replyAck = acknowledge
            state = ClaimCheckState.openingReply(state)
            delegate.doHttpBegin(traceId, authorization, affinity, EMPTY_EXTENSION)
        }

        private fun onClaimCheckData(data: DataFW) {
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
                doClaimCheckReset(traceId)
                delegate.doHttpAbort(traceId, authorization)
            } else {
                delegate.doHttpData(traceId, authorization, budgetId, reserved, flags, payload)
            }
        }

        private fun onClaimCheckEnd(end: EndFW) {
            val sequence = end.sequence()
            val acknowledge = end.acknowledge()
            val traceId = end.traceId()
            val authorization = end.authorization()

            replySeq = sequence
            state = ClaimCheckState.closeReply(state)
            delegate.doHttpEnd(traceId, authorization)
        }

        private fun onClaimCheckFlush(flush: FlushFW) {
            val sequence = flush.sequence()
            val acknowledge = flush.acknowledge()
            val traceId = flush.traceId()
            val authorization = flush.authorization()
            val budgetId = flush.budgetId()
            val reserved = flush.reserved()

            replySeq = sequence
            delegate.doHttpFlush(traceId, authorization, budgetId, reserved)
        }

        private fun onClaimCheckAbort(abort: AbortFW) {
            val sequence = abort.sequence()
            val acknowledge = abort.acknowledge()
            val traceId = abort.traceId()
            val authorization = abort.authorization()

            replySeq = sequence
            state = ClaimCheckState.closeReply(state)
            delegate.doHttpAbort(traceId, authorization)
        }

        private fun onClaimCheckWindow(window: WindowFW) {
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
            delegate.doHttpWindow(authorization, traceId, budgetId, padding, capabilities)
        }

        private fun onClaimCheckReset(reset: ResetFW) {
            val traceId = reset.traceId()
            delegate.doHttpReset(traceId, HEADER_STATUS_VALUE_400)
        }

        private fun cleanupTempFile() {
            try {
                tempFileOutputStream?.close()
                tempFile?.let { if (it.exists()) it.delete() }
            } catch (e: Exception) {
                // Log error if needed
            } finally {
                tempFile = null
                tempFileOutputStream = null
            }
        }

        private fun List<ByteArray>.concatToByteArray(): ByteArray {
            val total = sumOf { it.size }
            val out = ByteArray(total)
            var off = 0
            for (b in this) {
                System.arraycopy(b, 0, out, off, b.size)
                off += b.size
            }
            return out
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
        payload: Flyweight
    ) {
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
            .payload(payload.buffer(), payload.offset(), payload.sizeof())
            .build()
        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof())
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
        extension: Flyweight
    ) {
        val reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .extension(extension.buffer(), extension.offset(), extension.sizeof())
            .build()
        receiver.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof())
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
        val resetEx = httpResetExRW.wrap(extBuffer, 0, extBuffer.capacity())
            .typeId(httpTypeId)
            .headersItem { h -> h.name(HEADER_STATUS_NAME).value(status) }
            .headersItem { h -> h.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
            .build()
        doReset(receiver, originId, routedId, streamId, sequence, acknowledge, maximum, traceId, resetEx)
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
        return receiver
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