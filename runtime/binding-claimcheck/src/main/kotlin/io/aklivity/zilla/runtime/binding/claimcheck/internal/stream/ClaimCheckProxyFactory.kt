package io.aklivity.zilla.runtime.binding.claimcheck.internal.stream

import io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckConfiguration
import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.binding.function.MessageConsumer
import io.aklivity.zilla.runtime.engine.config.BindingConfig
import org.agrona.DirectBuffer
import org.agrona.MutableDirectBuffer
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.collections.Long2ObjectHashMap
import io.minio.MinioClient
import java.util.function.LongUnaryOperator

/**
 * ClaimCheckProxyFactory — binding-level factory and lifecycle manager.
 */
class ClaimCheckProxyFactory(
    private val config: ClaimCheckConfiguration,
    private val context: EngineContext
) : BindingHandler {

    companion object {
        private const val HTTP_TYPE_NAME = "http"
    }

    private val defaultWriteBuffer: MutableDirectBuffer = context.writeBuffer()
    private val defaultExtBuffer: MutableDirectBuffer = UnsafeBuffer(ByteArray(context.writeBuffer().capacity()))
    private val streamFactory: BindingHandler = context.streamFactory()
    private val supplyInitialId: LongUnaryOperator = LongUnaryOperator { v -> context.supplyInitialId(v) }
    val supplyReplyId: LongUnaryOperator = LongUnaryOperator { v -> context.supplyReplyId(v) }
    private val httpTypeId: Int = context.supplyTypeId(HTTP_TYPE_NAME)
    private val bindings = Long2ObjectHashMap<ClaimCheckBindingConfig>()
    private val minioClients = Long2ObjectHashMap<MinioClient>()

    init {
        println("ClaimCheckProxyFactory: Initialized with writeBuffer capacity=${defaultWriteBuffer.capacity()}, extBuffer capacity=${defaultExtBuffer.capacity()}")
    }

    private val streamHandler = ClaimCheckStreamHandler(
        factory = this,
        defaultWriteBuffer = defaultWriteBuffer,
        defaultExtBuffer = defaultExtBuffer,
        streamFactory = streamFactory,
        httpTypeId = httpTypeId,
        supplyReplyId = supplyReplyId,
        context = context
    )

    fun attach(binding: BindingConfig) {
        println("ClaimCheckProxyFactory: Attaching binding id=${binding.id}, name=${binding.name}")
        synchronized(bindings) {
            if (bindings.containsKey(binding.id)) {
                println("ClaimCheckProxyFactory: Binding id=${binding.id} already attached")
                return
            }
            try {
                val bindingConfig = ClaimCheckBindingConfig(binding)
                bindings[binding.id] = bindingConfig

                val opts = bindingConfig.options
                val minioClient = MinioClient.builder()
                    .endpoint(opts.endpoint)
                    .credentials(opts.accessKey, opts.secretKey)
                    .build()

                minioClients[binding.id] = minioClient
                println("ClaimCheckProxyFactory: Attached MinIO client for ${opts.endpoint}/${opts.bucket}")
            } catch (ex: Exception) {
                println("ClaimCheckProxyFactory: Error attaching binding id=${binding.id}: ${ex.message}")
                ex.printStackTrace()
            }
        }
    }

    fun detach(bindingId: Long) {
        println("ClaimCheckProxyFactory: Detaching binding id=$bindingId")
        synchronized(bindings) {
            bindings.remove(bindingId)
            minioClients.remove(bindingId)
            println("ClaimCheckProxyFactory: Detached binding id=$bindingId")
        }
    }

    override fun newStream(
        msgTypeId: Int,
        buffer: DirectBuffer?,
        index: Int,
        length: Int,
        sender: MessageConsumer?
    ): MessageConsumer? {
        println("ClaimCheckProxyFactory: newStream(msgTypeId=$msgTypeId, bufferCap=${buffer?.capacity() ?: 0}, idx=$index, len=$length)")
        if (buffer == null || sender == null) {
            println("ClaimCheckProxyFactory: null buffer or sender")
            return null
        }

        return MessageConsumer { typeId, buf, offset, len ->
            try {
                streamHandler.onStream(
                    msgTypeId = typeId,
                    buffer = buf,
                    offset = offset,
                    length = len,
                    receiver = sender,
                    bindings = bindings,
                    minioClients = minioClients
                )
            } catch (ex: Exception) {
                println("ClaimCheckProxyFactory: Error delegating stream frame (type=$typeId): ${ex.message}")
                ex.printStackTrace()
            }
        }
    }

    internal fun getBinding(routedId: Long): ClaimCheckBindingConfig? = bindings[routedId]
    internal fun getMinioClient(routedId: Long): MinioClient? = minioClients[routedId]
}