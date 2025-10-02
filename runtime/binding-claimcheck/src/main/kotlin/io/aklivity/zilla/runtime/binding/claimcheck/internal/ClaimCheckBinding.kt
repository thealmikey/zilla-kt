package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.Binding
import io.aklivity.zilla.runtime.engine.binding.BindingContext
import java.net.URL

/**
 * Entry point for the "claimcheck" binding.
 * Discovered by Zilla engine via SPI.
 */
class ClaimCheckBinding(val config: ClaimCheckConfiguration) : Binding {

    companion object {
        var NAME = "claimcheck"
    }

    override fun name(): String = "claimcheck"


//    // claimcheck binding is not an origin (we don't initiate traffic)
//    override fun originType(kind: KindConfig): String? = null
//
//    // claimcheck binding acts as a proxy
//    override fun routedType(kind: KindConfig): String? =
//        if (kind == KindConfig.PROXY) NAME else null

    override fun type(): URL? {
        return javaClass.getResource("schema/claimcheck.schema.patch.json")
    }

    override fun supply(context: EngineContext): BindingContext =
        ClaimCheckBindingContext(config, context)
}
