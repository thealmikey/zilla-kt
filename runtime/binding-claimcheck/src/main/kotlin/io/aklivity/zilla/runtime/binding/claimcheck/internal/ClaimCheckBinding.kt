package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.Binding
import io.aklivity.zilla.runtime.engine.binding.BindingContext
import io.aklivity.zilla.runtime.engine.config.KindConfig

/**
 * Entry point for the "claimcheck" binding.
 * Discovered by Zilla engine via SPI.
 */
class ClaimCheckBinding(val config: ClaimCheckConfiguration) : Binding {

    companion object {
        const val NAME = "claimcheck"
    }

    override fun name(): String = NAME

    // claimcheck binding is not an origin (we don't initiate traffic)
    override fun originType(kind: KindConfig): String? = null

    // claimcheck binding acts as a proxy
    override fun routedType(kind: KindConfig): String? =
        if (kind == KindConfig.PROXY) NAME else null

    override fun supply(context: EngineContext): BindingContext =
        ClaimCheckBindingContext(config, context)
}
