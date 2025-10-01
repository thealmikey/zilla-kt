package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.engine.config.OptionsConfig
import io.aklivity.zilla.runtime.engine.config.OptionsConfigAdapterSpi
import jakarta.json.Json
import jakarta.json.JsonObject
import io.aklivity.zilla.runtime.binding.claimcheck.config.*

class ClaimCheckOptionsConfigAdapter : OptionsConfigAdapterSpi {
    override fun kind(): OptionsConfigAdapterSpi.Kind? {
        return OptionsConfigAdapterSpi.Kind.BINDING
    }

    override fun type(): String = "claimcheck"

    override fun adaptFromJson(json: JsonObject?): OptionsConfig? =
        json?.let {
            val minio = it.getJsonObject("minio")
            val endpoint = minio.getString("endpoint", "http://localhost:9000")
            val accessKey = minio.getString("accessKey", "")
            val secretKey = minio.getString("secretKey", "")
            val bucket = minio.getString("bucket", "uploads")

            ClaimCheckOptionsConfig(
                    endpoint = endpoint,
                    accessKey = accessKey,
                    secretKey = secretKey,
                    bucket = bucket
            )
        }

    override fun adaptToJson(options: OptionsConfig?): JsonObject? {
        val config = options as? ClaimCheckOptionsConfig ?: return null

        return Json.createObjectBuilder()
                .add("endpoint", config.endpoint)
                .add("accessKey", config.accessKey)
                .add("secretKey", config.secretKey)
                .add("bucket", config.bucket)
                .build()
    }
}
