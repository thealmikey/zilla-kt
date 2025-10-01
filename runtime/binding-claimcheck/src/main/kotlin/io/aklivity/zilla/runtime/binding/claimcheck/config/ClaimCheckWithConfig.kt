package io.aklivity.zilla.runtime.binding.claimcheck.config

import io.aklivity.zilla.runtime.engine.config.WithConfig

data class ClaimCheckWithConfig(
    val ttl: Long = 3600L, // in seconds
    val maxPayloadSize: Long = 10485760L, // 10MB
    val inMemoryThreshold: Long = 4194304L, // 4MB
    val headers: Map<String, String> = emptyMap(),
    val presigned: Boolean = false
) : WithConfig()