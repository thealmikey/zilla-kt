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
        ClaimCheckProxyFactory(config, context).also {
            println("ClaimCheckBindingContext: Initialized factory for PROXY kind")
        }
    )

    override fun attach(binding: BindingConfig): BindingHandler? {
        println("ClaimCheckBindingContext: Entering attach for bindingId=${binding.id}, name=${binding.name}, kind=${binding.kind}")
        try {
            val factory = factories[binding.kind]
            if (factory == null) {
                println("ClaimCheckBindingContext: No factory found for kind=${binding.kind}")
                return null
            }
            factory.attach(binding)
            println("ClaimCheckBindingContext: Attached bindingId=${binding.id} to factory")
            return factory
        } catch (e: Exception) {
            println("ClaimCheckBindingContext: Error attaching bindingId=${binding.id}")
            e.printStackTrace()
            return null
        }
    }

    override fun detach(binding: BindingConfig) {
        println("ClaimCheckBindingContext: Entering detach for bindingId=${binding.id}, name=${binding.name}, kind=${binding.kind}")
        try {
            factories[binding.kind]?.detach(binding.id)
            println("ClaimCheckBindingContext: Detached bindingId=${binding.id}")
        } catch (e: Exception) {
            println("ClaimCheckBindingContext: Error detaching bindingId=${binding.id}")
            e.printStackTrace()
        }
    }
}