package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.ClaimCheckProxyFactory
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.config.BindingConfig
import io.aklivity.zilla.runtime.engine.config.KindConfig
import java.util.Collections.singletonMap

/**
 * Provides the runtime context for all "claimcheck" bindings.
 * Responsible for creating, attaching, and detaching factories per binding kind.
 */
class ClaimCheckBindingContext(
    private val config: ClaimCheckConfiguration,
    private val context: EngineContext
) : BindingContext {

    // For now, only PROXY kind is supported.
    // You can extend this later for SERVER or CLIENT if needed.
    private val factories: Map<KindConfig, ClaimCheckProxyFactory> = singletonMap(
        KindConfig.PROXY,
        ClaimCheckProxyFactory(config, context).also {
            println("ClaimCheckBindingContext: Initialized factory for kind=PROXY")
        }
    )

    /**
     * Called when Zilla attaches this binding during engine startup.
     * Returns a BindingHandler (the ClaimCheckProxyFactory) that will handle streams.
     */
    override fun attach(binding: BindingConfig): BindingHandler? {
        println("ClaimCheckBindingContext: Attaching binding id=${binding.id}, name=${binding.name}, kind=${binding.kind}")

        return try {
            val factory = factories[binding.kind]
            if (factory == null) {
                println("ClaimCheckBindingContext: No factory available for kind=${binding.kind}")
                null
            } else {
                factory.attach(binding)
                println("ClaimCheckBindingContext: Attached binding id=${binding.id} successfully")
                factory
            }
        } catch (ex: Exception) {
            println("ClaimCheckBindingContext: Error attaching binding id=${binding.id} (${ex.message})")
            ex.printStackTrace()
            null
        }
    }

    /**
     * Called when Zilla shuts down or a binding is reloaded.
     * Cleans up associated resources like MinIO clients.
     */
    override fun detach(binding: BindingConfig) {
        println("ClaimCheckBindingContext: Detaching binding id=${binding.id}, kind=${binding.kind}")

        try {
            val factory = factories[binding.kind]
            if (factory != null) {
                factory.detach(binding.id)
                println("ClaimCheckBindingContext: Detached binding id=${binding.id}")
            } else {
                println("ClaimCheckBindingContext: No factory found for kind=${binding.kind}")
            }
        } catch (ex: Exception) {
            println("ClaimCheckBindingContext: Error detaching binding id=${binding.id} (${ex.message})")
            ex.printStackTrace()
        }
    }
}
