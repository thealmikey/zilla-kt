package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.ClaimCheckStreamFactory
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.Binding
import io.aklivity.zilla.runtime.engine.binding.BindingContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.config.KindConfig

class ClaimCheckBinding(
    private val config: ClaimCheckBindingConfig
) : Binding {

    companion object {
        const val NAME = "claimcheck"
    }

    override fun name(): String = NAME

    // This binding only acts as a proxy
    override fun originType(kind: KindConfig): String? = null

    override fun routedType(kind: KindConfig): String? =
        if (kind == KindConfig.PROXY) NAME else null

    override fun supply(context: EngineContext): BindingContext? =
        ClaimCheckBindingContext(config, context)
}
