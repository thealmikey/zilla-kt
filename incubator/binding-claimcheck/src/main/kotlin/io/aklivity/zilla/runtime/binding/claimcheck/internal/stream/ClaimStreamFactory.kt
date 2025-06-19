package io.aklivity.zilla.runtime.binding.claim.internal

import io.aklivity.zilla.runtime.engine.binding.BindingContext
import io.aklivity.zilla.runtime.engine.binding.stream.StreamFactory

class ClaimStreamFactory(
    private val context: BindingContext,
    private val config: ClaimBindingConfig
) : StreamFactory {
    // We'll implement stream handling logic here later
}
