package io.aklivity.zilla.runtime.binding.claimcheck.internal.config
import io.aklivity.zilla.runtime.engine.config.ConditionConfig
import io.aklivity.zilla.runtime.engine.config.ConfigBuilder

class ClaimCheckConditionConfigBuilder<T>(
    private val mapper: (ConditionConfig) -> T
) : ConfigBuilder<T, ClaimCheckConditionConfigBuilder<T>>() {
    private var path: String? = null
    private var method: String? = null

    fun path(path: String) = apply { this.path = path }
    fun method(method: String) = apply { this.method = method }

    override fun build(): T = mapper(ClaimCheckConditionConfig(
        path ?: "/store",
        method ?: "POST"
    ))

    override fun thisType(): Class<ClaimCheckConditionConfigBuilder<T>> =
        this::class.java as Class<ClaimCheckConditionConfigBuilder<T>>
}