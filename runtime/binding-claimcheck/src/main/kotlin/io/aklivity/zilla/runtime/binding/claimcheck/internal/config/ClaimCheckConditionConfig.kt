package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.HttpBeginExFW
import io.aklivity.zilla.runtime.engine.config.ConditionConfig

data class ClaimCheckConditionConfig(
    val path: String,
    val method: String? // Changed to nullable
): ConditionConfig() {
    fun matches(beginEx: HttpBeginExFW): Boolean {
        println("ClaimCheckConditionConfig: Entering matches with path=$path, method=$method")
        try {
            var hasPath = false
            var hasMethod = method == null // True if method is not specified

            beginEx.headers().forEach { h: HttpHeaderFW ->
                val name = h.name().asString()
                val value = h.value().asString()
                println("ClaimCheckConditionConfig: Checking header: $name=$value")
                if (name == ":path" && value.startsWith(path)) {
                    hasPath = true
                    println("ClaimCheckConditionConfig: Path match: $value starts with $path")
                }
                if (method != null && name == ":method" && value.equals(method, ignoreCase = true)) {
                    hasMethod = true
                    println("ClaimCheckConditionConfig: Method match: $value == $method")
                }
            }
            val result = hasPath && hasMethod
            println("ClaimCheckConditionConfig: Exiting matches with hasPath=$hasPath, hasMethod=$hasMethod, result=$result")
            return result
        } catch (e: Exception) {
            println("ClaimCheckConditionConfig: Error in matches")
            e.printStackTrace()
            return false
        }
    }
}