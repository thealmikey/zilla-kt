package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckBindingConfig
import io.aklivity.zilla.runtime.engine.Configuration
import io.aklivity.zilla.runtime.engine.binding.Binding
import io.aklivity.zilla.runtime.engine.binding.BindingFactorySpi

class ClaimCheckBindingFactorySpi : BindingFactorySpi {

    override fun type(): String = ClaimCheckBinding.NAME

    override fun create(config: Configuration?): Binding? {
        return ClaimCheckBinding(config as ClaimCheckBindingConfig)
    }
}
