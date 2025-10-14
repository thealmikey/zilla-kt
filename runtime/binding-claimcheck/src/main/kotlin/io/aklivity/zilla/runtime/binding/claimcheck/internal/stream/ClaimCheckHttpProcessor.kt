package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream

import io.aklivity.zilla.runtime.binding.claimcheck.config.ClaimCheckRouteConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.*
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.*
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer
import io.minio.*
import io.minio.http.Method
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.function.LongUnaryOperator
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.HttpBeginExFW

/**
 * ClaimCheckHttpProcessor – per-transaction HTTP handler (one instance per HTTP exchange).
 *
 * It emits frames to `http` (the MessageConsumer supplied by the binding) and
 * receives incoming BEGIN/DATA/END frames from the StreamHandler.
 */
class ClaimCheckHttpProcessor(
    private val http: MessageConsumer,
    val originId: Long,
    val routedId: Long,
    val initialId: Long,
    private val sequence: Long,
    private val acknowledge: Long,
    private val maximum: Int,
    private val traceId: Long,
    private val authorization: Long,
    private val binding: ClaimCheckBindingConfig,
    private val route: ClaimCheckRouteConfig,
    private val minioClient: MinioClient,
    private val httpTypeId: Int,
    private val supplyReplyId: LongUnaryOperator,
    private val writeBuffer: MutableDirectBuffer,
    private val extBuffer: MutableDirectBuffer
) {
    companion object {
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val MAX_RESPONSE_SIZE = 10 * 1024 * 1024L // 10MB
        const val BUFFER_SIZE = 8192

        val HEADER_STATUS_NAME = String8FW(":status")
        val HEADER_METHOD_NAME = String8FW(":method")
        val HEADER_PATH_NAME = String8FW(":path")
        val HEADER_CONTENT_TYPE_NAME = String8FW("content-type")
        val HEADER_CONTENT_LENGTH_NAME = String8FW("content-length")
        val HEADER_X_CLAIM_NAME = String8FW("X-Claim")

        val STATUS_200 = String16FW("200")
        val STATUS_400 = String16FW("400")
        val STATUS_404 = String16FW("404")
        val STATUS_405 = String16FW("405")
        val STATUS_408 = String16FW("408")
        val STATUS_413 = String16FW("413")
        val STATUS_500 = String16FW("500")
    }

    private val beginRW = BeginFW.Builder()
    private val dataRW = DataFW.Builder()
    private val endRW = EndFW.Builder()
    private val resetRW = ResetFW.Builder()
    private val windowRW = WindowFW.Builder()
    private val httpBeginExRW = HttpBeginExFW.Builder()
    private val httpResetExRW = HttpResetExFW.Builder()
    val httpBeginExRO = HttpBeginExFW()


    // accumulator buffer for POST/PUT bodies (in-memory). Use route.with.maxPayloadSize
    private var payloadBuffer: UnsafeBuffer? = null
    private var writtenBytes: Int = 0

    // called from StreamHandler when we get the Begin
    fun onHttpBegin(begin: BeginFW) {
        println("ClaimCheckHttpProcessor[$traceId]: onHttpBegin(initialId=$initialId)")
        try {
            val extension = begin.extension()
            val beginEx = httpBeginExRO.tryWrap(extension.buffer(), extension.offset(), extension.limit())

            if (beginEx == null) {
                println("ClaimCheckHttpProcessor[$traceId]: Missing or invalid HttpBeginExFW extension")
                doHttpReset(traceId, STATUS_400)
                return
            }

            // Safely iterate headers
            val method = beginEx.headers()
                .matchFirst { h -> h.name().asString() == ":method" }
                ?.value()?.asString()
            val path = beginEx.headers()
                .matchFirst { h -> h.name().asString() == ":path" }
                ?.value()?.asString()

            println("ClaimCheckHttpProcessor[$traceId]: method=$method path=$path")

            when (method?.uppercase()) {
                "GET" -> {
                    handleGet(path)
                }
                "POST", "PUT" -> {
                    val maxPayload = route.with?.maxPayloadSize?.toInt() ?: 10_485_760
                    payloadBuffer = UnsafeBuffer(ByteArray(maxPayload))
                    writtenBytes = 0
                    doHttpWindow(authorization, traceId, 0L, 0, 0)
                    println("ClaimCheckHttpProcessor[$traceId]: Window granted ($maxPayload bytes), waiting for POST/PUT data")
                }
                else -> {
                    println("ClaimCheckHttpProcessor[$traceId]: Unsupported method ($method) -> reset 405")
                    doHttpReset(traceId, STATUS_405)
                }
            }
        } catch (ex: Exception) {
            println("ClaimCheckHttpProcessor[$traceId]: Error in onHttpBegin: ${ex.message}")
            ex.printStackTrace()
            doHttpReset(traceId, STATUS_500)
        }
    }

    // called from StreamHandler when we get Data for this stream
    fun onHttpData(data: DataFW) {
        val payload = data.payload()
        val size = payload.sizeof()
        val tId = data.traceId()
        try {
            if (payloadBuffer == null) {
                println("ClaimCheckHttpProcessor[$traceId]: Received DATA but no accumulator buffer - ignoring")
                return
            }
            if (size == 0) {
                println("ClaimCheckHttpProcessor[$traceId]: Received DATA chunk of 0 bytes (trace=$tId)")
            } else {
                // copy bytes into accumulator
                payload.buffer().getBytes(payload.offset(), payloadBuffer!!.byteArray(), writtenBytes, size)
                writtenBytes += size
                println("ClaimCheckHttpProcessor[$traceId]: Received chunk ($size bytes, total=$writtenBytes)")
            }

            // grant more window equal to bytes consumed to keep upstream sending
            doHttpWindow(authorization, tId, 0L, size, 0)

        } catch (ex: Exception) {
            println("ClaimCheckHttpProcessor[$traceId]: Error in onHttpData: ${ex.message}")
            ex.printStackTrace()
            doHttpReset(tId, STATUS_500)
        }
    }

    fun onHttpEnd(end: EndFW) {
        val tId = end.traceId()
        println("ClaimCheckHttpProcessor[$traceId]: onHttpEnd(initialId=$initialId, writtenBytes=$writtenBytes)")
        try {
            val buffer = payloadBuffer ?: UnsafeBuffer(ByteArray(0))
            val size = writtenBytes

            // empty-body check allowed; still create object key for empty payload if needed
            val objectKey = UUID.randomUUID().toString()

            // upload to MinIO
            val stream = ByteArrayInputStream(buffer.byteArray(), 0, size)
            minioClient.putObject(
                PutObjectArgs.builder()
                    .bucket(binding.options.bucket)
                    .`object`(objectKey)
                    .stream(stream, size.toLong(), -1)
                    .contentType("application/octet-stream")
                    .build()
            )
            println("ClaimCheckHttpProcessor[$traceId]: Uploaded objectKey=$objectKey")

            // create presigned url if requested
            val presignedUrl: String? = if (route.with?.presigned == true) {
                minioClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                        .method(Method.GET)
                        .bucket(binding.options.bucket)
                        .`object`(objectKey)
                        .expiry(route.with?.ttl?.toInt() ?: 3600)
                        .build()
                )
            } else null

            // Reply on reply stream id (flip low bit)
            val replyId = supplyReplyId.applyAsLong(initialId)

            doHttpBegin(replyId, tId, authorization) { headers ->
                headers
                    .item { it.name(HEADER_STATUS_NAME).value(STATUS_200) }
                    .item { it.name(HEADER_CONTENT_LENGTH_NAME).value(String16FW("0")) }
                    .apply {
                        val claimValue = presignedUrl ?: objectKey

                        // add X-Claim header (String16FW)
                        item { h -> h.name(HEADER_X_CLAIM_NAME).value(String16FW(claimValue)) }

                        // add any configured response headers from route.with.headers (with 'uuid' substitution)
                        route.with?.headers?.forEach { (name, value) ->
                            val replaced = value.replace("uuid", objectKey)
                            item { h ->
                                h.name(String8FW(name))
                                    .value(String16FW(replaced))
                            }
                        }
                    }
            }


            doHttpEnd(replyId, tId, authorization)
            println("ClaimCheckHttpProcessor[$traceId]: Stored object=$objectKey, presigned=$presignedUrl")

        } catch (ex: Exception) {
            println("ClaimCheckHttpProcessor[$traceId]: Error storing to MinIO: ${ex.message}")
            ex.printStackTrace()
            doHttpReset(tId, STATUS_500)
        } finally {
            // free the payload buffer reference
            payloadBuffer = null
            writtenBytes = 0
        }
    }

    // GET handling (reply with object content)
    private fun handleGet(path: String?) {
        if (path == null) {
            println("ClaimCheckHttpProcessor[$traceId]: GET missing path -> 400")
            doHttpReset(traceId, STATUS_400)
            return
        }

        println("ClaimCheckHttpProcessor[$traceId]: Handling GET path=$path")
        val objectKey = extractObjectKeyFromPath(path, binding.options.bucket)
        if (objectKey == null) {
            println("ClaimCheckHttpProcessor[$traceId]: GET object key extraction failed -> 404")
            doHttpReset(traceId, STATUS_404)
            return
        }

        try {
            // use statObject as requested
            val stat = minioClient.statObject(
                StatObjectArgs.builder()
                    .bucket(binding.options.bucket)
                    .`object`(objectKey)
                    .build()
            )

            val replyId = supplyReplyId.applyAsLong(initialId)
            val contentType = stat.contentType() ?: "application/octet-stream"
            val length = stat.size()

            // Begin reply
            doHttpBegin(replyId, traceId, authorization) { headers ->
                headers
                    .item { it.name(HEADER_STATUS_NAME).value(STATUS_200) }
                    .item { it.name(HEADER_CONTENT_TYPE_NAME).value(String16FW(contentType)) }
                    .item { it.name(HEADER_CONTENT_LENGTH_NAME).value(String16FW(length.toString())) }
            }

            val buffer = ByteArray(BUFFER_SIZE)
            minioClient.getObject(
                GetObjectArgs.builder()
                    .bucket(binding.options.bucket)
                    .`object`(objectKey)
                    .build()
            ).use { input ->
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > MAX_RESPONSE_SIZE) {
                        println("ClaimCheckHttpProcessor[$traceId]: GET response too large, aborting")
                        doHttpReset(traceId, STATUS_413)
                        return
                    }
                    val payload = UnsafeBuffer(buffer, 0, read)
                    doHttpData(replyId, traceId, authorization, 0L, read, payload)
                }
            }

            doHttpEnd(replyId, traceId, authorization)
            println("ClaimCheckHttpProcessor[$traceId]: GET success for $objectKey")

        } catch (ex: Exception) {
            println("ClaimCheckHttpProcessor[$traceId]: GET failed: ${ex.message}")
            ex.printStackTrace()
            doHttpReset(traceId, STATUS_404)
        }
    }

    // attempt to decode object key from :path or presigned url
    private fun extractObjectKeyFromPath(path: String, bucket: String): String? {
        return try {
            val decoded = URLDecoder.decode(path, StandardCharsets.UTF_8)
            val uri = URI.create(decoded)
            val segments = uri.path.split("/").filter { it.isNotEmpty() }
            when {
                segments.firstOrNull() == bucket -> segments.drop(1).joinToString("/")
                uri.query?.contains("X-Amz-Signature") == true -> segments.lastOrNull()
                else -> segments.lastOrNull()
            }
        } catch (ex: Exception) {
            println("ClaimCheckHttpProcessor: Failed to parse path=$path (${ex.message})")
            null
        }
    }

    // ----------------- Frame emission helpers -----------------
    private fun doHttpBegin(
        streamId: Long,
        traceId: Long,
        authorization: Long,
        headers: (Array32FW.Builder<HttpHeaderFW.Builder, HttpHeaderFW>) -> Unit
    ) {
        val httpBeginEx = httpBeginExRW.wrap(extBuffer, 0, extBuffer.capacity())
            .typeId(httpTypeId)
            .headers(headers)
            .build()

        val begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(streamId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .extension(httpBeginEx.buffer(), httpBeginEx.offset(), httpBeginEx.sizeof())
            .build()

        http.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof())
    }

    private fun doHttpData(
        streamId: Long,
        traceId: Long,
        authorization: Long,
        budgetId: Long,
        reserved: Int,
        payload: DirectBuffer
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
            .budgetId(budgetId)
            .reserved(reserved)
            .payload(payload, 0, reserved)
            .build()

        http.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof())
    }

    private fun doHttpEnd(streamId: Long, traceId: Long, authorization: Long) {
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

        http.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof())
    }

    private fun doHttpReset(traceId: Long, status: String16FW) {
        try {
            val resetEx = httpResetExRW.wrap(extBuffer, 0, extBuffer.capacity())
                .typeId(httpTypeId)
                .headersItem { it.name(HEADER_STATUS_NAME).value(status) }
                .headersItem { it.name(HEADER_CONTENT_LENGTH_NAME).value("0") }
                .build()

            val reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .originId(originId)
                .routedId(routedId)
                .streamId(initialId)
                .sequence(sequence)
                .acknowledge(acknowledge)
                .maximum(maximum)
                .traceId(traceId)
                .extension(resetEx.buffer(), resetEx.offset(), resetEx.sizeof())
                .build()

            http.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof())
            println("ClaimCheckHttpProcessor[$traceId]: Sent HTTP reset for initialId=$initialId with status=${status.asString()}")
        } catch (ex: Exception) {
            println("ClaimCheckHttpProcessor[$traceId]: Failed to send reset: ${ex.message}")
            ex.printStackTrace()
        }
    }

    private fun doHttpWindow(authorization: Long, traceId: Long, budgetId: Long, padding: Int, capabilities: Int) {
        val window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
            .originId(originId)
            .routedId(routedId)
            .streamId(initialId)
            .sequence(sequence)
            .acknowledge(acknowledge)
            .maximum(maximum)
            .traceId(traceId)
            .authorization(authorization)
            .budgetId(budgetId)
            .padding(padding)
            .capabilities(capabilities)
            .build()

        http.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof())
    }
}
