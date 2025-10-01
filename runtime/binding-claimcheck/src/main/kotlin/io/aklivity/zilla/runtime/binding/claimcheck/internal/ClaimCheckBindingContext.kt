package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.ClaimCheckProxyFactory
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.config.BindingConfig
import io.aklivity.zilla.runtime.engine.config.KindConfig
import java.util.Collections.singletonMap


class ClaimCheckBindingContext(
    val config: ClaimCheckConfiguration,
    val context: EngineContext
) : BindingContext {
    private val factories: Map<KindConfig, ClaimCheckProxyFactory> = singletonMap(
        KindConfig.PROXY,
        ClaimCheckProxyFactory(config,context)
    )
    override fun attach(binding: BindingConfig): BindingHandler? {
        val factory = factories[binding.kind]
        factory?.attach(binding)
        return factory
    }
    override fun detach(binding: BindingConfig) {
        factories[binding.kind]?.detach(binding.id)
    }
}