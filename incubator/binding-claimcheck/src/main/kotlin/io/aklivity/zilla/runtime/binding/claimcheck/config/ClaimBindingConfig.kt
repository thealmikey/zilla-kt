package io.aklivity.zilla.runtime.binding.claim.internal.config

data class ClaimBindingConfig(
    val minio: MinioConfig = MinioConfig()
) {
    data class MinioConfig(
        val endpoint: String = "http://localhost:9000",
        val accessKey: String = "",
        val secretKey: String = "",
        val bucket: String = "uploads"
    )
}
