package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckBinding
import io.aklivity.zilla.runtime.engine.config.ConditionConfig
import io.aklivity.zilla.runtime.engine.config.ConditionConfigAdapterSpi
import jakarta.json.Json
import jakarta.json.JsonObject
import jakarta.json.bind.adapter.JsonbAdapter

class ClaimCheckConditionConfigAdapter : ConditionConfigAdapterSpi, JsonbAdapter<ConditionConfig, JsonObject> {
    override fun type(): String? {
        return ClaimCheckBinding.NAME
    }

    companion object {
        const val PATH_NAME = "path"
        const val METHOD_NAME = "method"
        const val HEADERS_NAME = "headers"
    }

    override fun adaptToJson(config: ConditionConfig): JsonObject {
        println("ClaimCheckConditionConfigAdapter: Entering adaptToJson")
        try {
            val condition = config as ClaimCheckConditionConfig
            val headersBuilder = Json.createObjectBuilder()
                .add(":path", condition.path)
                .add(":method", condition.method)
            val result = Json.createObjectBuilder()
                .add(HEADERS_NAME, headersBuilder)
                .build()
            println("ClaimCheckConditionConfigAdapter: Exiting adaptToJson with result: $result")
            return result
        } catch (e: Exception) {
            println("ClaimCheckConditionConfigAdapter: Error in adaptToJson")
            e.printStackTrace()
            throw e
        }
    }

    override fun adaptFromJson(json: JsonObject): ConditionConfig {
        println("ClaimCheckConditionConfigAdapter: Entering adaptFromJson with json: $json")
        try {
            val headers = json.getJsonObject(HEADERS_NAME)
            val path = headers?.getString(":path", "/store") ?: "/store"
            val method = headers?.getString(":method", "POST") ?: "POST"
            val condition = ClaimCheckConditionConfig(path, method)
            println("ClaimCheckConditionConfigAdapter: Exiting adaptFromJson with path=$path, method=$method")
            return condition
        } catch (e: Exception) {
            println("ClaimCheckConditionConfigAdapter: Error in adaptFromJson")
            e.printStackTrace()
            throw e
        }
    }
}