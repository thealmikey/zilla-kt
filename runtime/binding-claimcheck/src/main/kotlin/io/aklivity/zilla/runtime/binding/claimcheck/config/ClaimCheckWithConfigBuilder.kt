package io.aklivity.zilla.runtime.binding.claimcheck.config

import io.aklivity.zilla.runtime.engine.config.ConfigBuilder
import io.aklivity.zilla.runtime.engine.config.WithConfig

class ClaimCheckWithConfigBuilder<T>(
    private val mapper: (WithConfig) -> T
) : ConfigBuilder<T, ClaimCheckWithConfigBuilder<T>>() {
    private var ttl: Long? = null
    private var maxPayloadSize: Long? = null
    private var inMemoryThreshold: Long? = null
    private var headers: MutableMap<String, String> = mutableMapOf()
    private var presigned: Boolean = false

    fun ttl(ttl: Long) = apply { this.ttl = ttl }
    fun maxPayloadSize(maxPayloadSize: Long) = apply { this.maxPayloadSize = maxPayloadSize }
    fun inMemoryThreshold(inMemoryThreshold: Long) = apply { this.inMemoryThreshold = inMemoryThreshold }
    fun header(name: String, value: String) = apply { headers[name] = value }
    fun presigned(presigned: Boolean) = apply { this.presigned = presigned }

    override fun build(): T = mapper(ClaimCheckWithConfig(
        ttl ?: 3600L,
        maxPayloadSize ?: 10485760L,
        inMemoryThreshold ?: 4194304L,
        headers,
        presigned
    ))

    override fun thisType(): Class<ClaimCheckWithConfigBuilder<T>> =
        this::class.java as Class<ClaimCheckWithConfigBuilder<T>>
}