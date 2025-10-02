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

    override fun adaptFromJson(json: JsonObject?): OptionsConfig? {
        println("ClaimCheckOptionsConfigAdapter: Entering adaptFromJson with json: $json")
        try {
            val config = json?.let {
                ClaimCheckOptionsConfig(
                    endpoint = it.getString("endpoint", "http://localhost:9000"),
                    accessKey = it.getString("accessKey", ""),
                    secretKey = it.getString("secretKey", ""),
                    bucket = it.getString("bucket", "uploads")
                )
            }
            println("ClaimCheckOptionsConfigAdapter: Exiting adaptFromJson with config: $config")
            return config
        } catch (e: Exception) {
            println("ClaimCheckOptionsConfigAdapter: Error in adaptFromJson")
            e.printStackTrace()
            throw e
        }
    }

    override fun adaptToJson(options: OptionsConfig?): JsonObject? {
        println("ClaimCheckOptionsConfigAdapter: Entering adaptToJson")
        try {
            val config = options as? ClaimCheckOptionsConfig ?: return null
            val result = Json.createObjectBuilder()
                .add("endpoint", config.endpoint)
                .add("accessKey", config.accessKey)
                .add("secretKey", config.secretKey)
                .add("bucket", config.bucket)
                .build()
            println("ClaimCheckOptionsConfigAdapter: Exiting adaptToJson with result: $result")
            return result
        } catch (e: Exception) {
            println("ClaimCheckOptionsConfigAdapter: Error in adaptToJson")
            e.printStackTrace()
            throw e
        }
    }
}