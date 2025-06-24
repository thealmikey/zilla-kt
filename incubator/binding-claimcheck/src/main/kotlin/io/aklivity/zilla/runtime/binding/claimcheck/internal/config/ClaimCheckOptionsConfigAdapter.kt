package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.engine.config.OptionsConfig
import io.aklivity.zilla.runtime.engine.config.OptionsConfigAdapterSpi
import jakarta.json.Json
import jakarta.json.JsonObject

class ClaimCheckOptionsConfigAdapter : OptionsConfigAdapterSpi {
    override fun kind(): OptionsConfigAdapterSpi.Kind? {
        return OptionsConfigAdapterSpi.Kind.BINDING
    }

    override fun type(): String = "claim"

    override fun adaptFromJson(json: JsonObject?): OptionsConfig? =
        json?.let {
            val minio = it.getJsonObject("minio")
            val endpoint = minio.getString("endpoint", "http://localhost:9000")
            val accessKey = minio.getString("accessKey", "")
            val secretKey = minio.getString("secretKey", "")
            val bucket = minio.getString("bucket", "uploads")

            ClaimCheckBindingConfig(
                minio = ClaimCheckBindingConfig.MinioConfig(
                    endpoint = endpoint,
                    accessKey = accessKey,
                    secretKey = secretKey,
                    bucket = bucket
                )
            )
        }

    override fun adaptToJson(options: OptionsConfig?): JsonObject? {
        val config = options as? ClaimCheckBindingConfig ?: return null

        return Json.createObjectBuilder()
            .add("minio", Json.createObjectBuilder()
                .add("endpoint", config.minio.endpoint)
                .add("accessKey", config.minio.accessKey)
                .add("secretKey", config.minio.secretKey)
                .add("bucket", config.minio.bucket)
            )
            .build()
    }
}
