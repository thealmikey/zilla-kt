package io.aklivity.zilla.runtime.binding.claimcheck.config

import io.aklivity.zilla.runtime.binding.claimcheck.internal.ClaimCheckBinding
import io.aklivity.zilla.runtime.engine.config.WithConfig
import io.aklivity.zilla.runtime.engine.config.WithConfigAdapterSpi
import jakarta.json.Json
import jakarta.json.JsonObject
import jakarta.json.bind.adapter.JsonbAdapter
import kotlin.collections.associate
import kotlin.collections.emptyMap

class ClaimCheckWithConfigAdapter : WithConfigAdapterSpi, JsonbAdapter<WithConfig, JsonObject> {

    override fun type(): String? {
        return ClaimCheckBinding.NAME
    }

    private companion object {
        const val TTL_NAME = "ttl"
        const val MAX_PAYLOAD_SIZE_NAME = "maxPayloadSize"
        const val IN_MEMORY_THRESHOLD_NAME = "inMemoryThreshold"
        const val HEADERS_NAME = "headers"
        const val PRESIGNED_NAME = "presigned"
    }

    override fun adaptToJson(config: WithConfig): JsonObject {
        println("ClaimCheckWithConfigAdapter: Entering adaptToJson")
        try {
            val with = config as ClaimCheckWithConfig
            val objectBuilder = Json.createObjectBuilder()
            objectBuilder.add(TTL_NAME, with.ttl)
            objectBuilder.add(MAX_PAYLOAD_SIZE_NAME, with.maxPayloadSize)
            objectBuilder.add(IN_MEMORY_THRESHOLD_NAME, with.inMemoryThreshold)
            if (with.headers.isNotEmpty()) {
                val headersBuilder = Json.createObjectBuilder()
                with.headers.forEach { (key, value) -> headersBuilder.add(key, value) }
                objectBuilder.add(HEADERS_NAME, headersBuilder)
            }
            objectBuilder.add(PRESIGNED_NAME, with.presigned)
            val result = objectBuilder.build()
            println("ClaimCheckWithConfigAdapter: Exiting adaptToJson with result: $result")
            return result
        } catch (e: Exception) {
            println("ClaimCheckWithConfigAdapter: Error in adaptToJson")
            e.printStackTrace()
            throw e
        }
    }

    override fun adaptFromJson(json: JsonObject): WithConfig {
        println("ClaimCheckWithConfigAdapter: Entering adaptFromJson with json: $json")
        try {
            val ttl = json.getJsonNumber(TTL_NAME)?.longValue() ?: 3600L
            val maxPayloadSize = json.getJsonNumber(MAX_PAYLOAD_SIZE_NAME)?.longValue() ?: 10485760L
            val inMemoryThreshold = json.getJsonNumber(IN_MEMORY_THRESHOLD_NAME)?.longValue() ?: 4194304L
            val headers: Map<String, String> = json.getJsonObject(HEADERS_NAME)?.let { headersObj ->
                headersObj.entries.associate { it.key to it.value.toString() }
            } ?: emptyMap()
            val presigned = json.getBoolean(PRESIGNED_NAME, false)
            val config = ClaimCheckWithConfig(ttl, maxPayloadSize, inMemoryThreshold, headers, presigned)
            println("ClaimCheckWithConfigAdapter: Exiting adaptFromJson with config: $config")
            return config
        } catch (e: Exception) {
            println("ClaimCheckWithConfigAdapter: Error in adaptFromJson")
            e.printStackTrace()
            throw e
        }
    }
}