package io.aklivity.zilla.runtime.binding.claimcheck.config

data class MinioConfig(
    val endpoint: String = "http://localhost:9000",
    val accessKey: String = "",
    val secretKey: String = "",
    val bucket: String = "uploads"
)