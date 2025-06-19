package io.aklivity.zilla.runtime.binding.claim.internal.config

import io.aklivity.zilla.runtime.engine.config.OptionsConfigAdapterSpi
import jakarta.json.JsonObject

class ClaimOptionsConfigAdapter : OptionsConfigAdapterSpi {
    override fun type(): String = "claim"

    override fun adapt(type: String?, options: JsonObject?): Any =
        options?.let {
            val minio = it.getJsonObject("minio")
            val endpoint = minio.getString("endpoint", "http://localhost:9000")
            val accessKey = minio.getString("accessKey", "")
            val secretKey = minio.getString("secretKey", "")
            val bucket = minio.getString("bucket", "uploads")

            ClaimBindingConfig(
                minio = ClaimBindingConfig.MinioConfig(
                    endpoint = endpoint,
                    accessKey = accessKey,
                    secretKey = secretKey,
                    bucket = bucket
                )
            )
        } ?: ClaimBindingConfig()
}
