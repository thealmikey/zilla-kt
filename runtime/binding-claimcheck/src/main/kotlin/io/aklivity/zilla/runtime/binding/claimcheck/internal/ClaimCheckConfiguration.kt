package io.aklivity.zilla.runtime.binding.claimcheck.internal

import io.aklivity.zilla.runtime.engine.Configuration

class ClaimCheckConfiguration(config: Configuration) : Configuration(CLAIMCHECK_CONFIG, config) {
    companion object {
        private val CLAIMCHECK_CONFIG: ConfigurationDef

        init {
            val config = ConfigurationDef("zilla.binding.claimcheck")
            CLAIMCHECK_CONFIG = config
        }
    }
}
