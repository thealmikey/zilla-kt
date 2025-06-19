package io.aklivity.zilla.runtime.binding.claim.internal

import io.aklivity.zilla.runtime.engine.EngineContext
import io.aklivity.zilla.runtime.engine.binding.Binding
import io.aklivity.zilla.runtime.engine.binding.BindingContext
import io.aklivity.zilla.runtime.engine.binding.stream.StreamFactory


class ClaimBinding(
    context: BindingContext,
    config: ClaimBindingConfig // (we'll define this next)
) : Binding {

    var clam = new ClaimCheckExFW()

    private val factories: Map<Class<*>, StreamFactory> = mapOf(
        StreamFactory::class.java to ClaimStreamFactory(context, config)
    )

    override fun <T : StreamFactory?> attach(factoryType: Class<T>?): T? =
        factories[factoryType] as? T
}
