package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.engine.config.OptionsConfig

data class ClaimCheckBindingConfig(
    val minio: MinioConfig = MinioConfig()
): OptionsConfig() {
    data class MinioConfig(
        val endpoint: String = "http://localhost:9000",
        val accessKey: String = "",
        val secretKey: String = "",
        val bucket: String = "uploads"
    )
}
