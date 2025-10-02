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
    }

    override fun adaptToJson(config: ConditionConfig): JsonObject {
        val condition = config as ClaimCheckConditionConfig
        return Json.createObjectBuilder()
            .add(PATH_NAME, condition.path)
            .add(METHOD_NAME, condition.method)
            .build()
    }

    override fun adaptFromJson(json: JsonObject): ConditionConfig {
        val path = json.getString(PATH_NAME, "/store")
        val method = json.getString(METHOD_NAME, "POST")
        return ClaimCheckConditionConfig(path, method)
    }
}