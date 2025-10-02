package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.engine.Configuration
import io.aklivity.zilla.runtime.engine.binding.Binding
import io.aklivity.zilla.runtime.engine.binding.BindingFactorySpi

class ClaimCheckBindingFactorySpi : BindingFactorySpi {



    override fun type(): String = "claimcheck"



    override fun create(config: Configuration): ClaimCheckBinding {
        return ClaimCheckBinding(ClaimCheckConfiguration(config))
    }

}
