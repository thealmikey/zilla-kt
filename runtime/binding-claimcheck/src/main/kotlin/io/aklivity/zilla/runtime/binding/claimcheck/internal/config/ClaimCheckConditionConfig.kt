package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.HttpHeaderFW
import io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.HttpBeginExFW
import io.aklivity.zilla.runtime.engine.config.ConditionConfig

/**
 * Defines a condition for matching HTTP routes in the claimcheck binding.
 * Both path and method are evaluated in a flyweight-safe way.
 */
data class ClaimCheckConditionConfig(
    val path: String,
    val method: String? = null
) : ConditionConfig() {

    fun matches(beginEx: HttpBeginExFW): Boolean {
        try {
            var hasPath = false
            var hasMethod = (method == null) // null = "match any"

            beginEx.headers().forEach { header: HttpHeaderFW ->
                val name = header.name().asString()
                val value = header.value().asString()

                when (name) {
                    ":path" -> {
                        // Normalise both sides to ensure leading slash consistency
                        val normalizedPath = if (path.startsWith("/")) path else "/$path"
                        if (value.startsWith(normalizedPath)) {
                            hasPath = true
                        }
                    }
                    ":method" -> {
                        if (method != null && value.equals(method, ignoreCase = true)) {
                            hasMethod = true
                        }
                    }
                }

                // Small optimisation: early exit if both true
//                if (hasPath && hasMethod) {return@forEach true
            }

            return hasPath && hasMethod
        } catch (ex: Exception) {
            println("ClaimCheckConditionConfig: Error matching route (path=$path, method=$method): ${ex.message}")
            ex.printStackTrace()
            return false
        }
    }
}
