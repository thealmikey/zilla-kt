package io.aklivity.zilla.runtime.binding.claimcheck.internal.config


import io.aklivity.zilla.runtime.binding.http.internal.types.stream.HttpBeginExFW
import io.aklivity.zilla.runtime.binding.http.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.engine.config.ConditionConfig

data class ClaimCheckConditionConfig(
    val path: String,
    val method: String
): ConditionConfig() {
    fun matches(beginEx: HttpBeginExFW): Boolean {
        var hasPath = false
        var hasMethod = false

        beginEx.headers().forEach { h: HttpHeaderFW ->
            val name = h.name().asString()
            val value = h.value().asString()
            if (name == ":path" && value.startsWith(path)) {
                hasPath = true
            }
            if (name == ":method" && value.equals(method, ignoreCase = true)) {
                hasMethod = true
            }
        }
        return hasPath && hasMethod
    }
}
