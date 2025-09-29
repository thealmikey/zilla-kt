package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.internal.stream.ClaimCheckStreamFactory
import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.BindingContext
import io.aklivity.zilla.runtime.engine.binding.BindingHandler
import io.aklivity.zilla.runtime.engine.config.BindingConfig

class ClaimCheckBindingContext(
    private val config: ClaimCheckBindingConfig,
    private val context: EngineContext
) : BindingContext {

    override fun attach(binding: BindingConfig): BindingHandler =
        ClaimCheckStreamFactory(context, config)
}
