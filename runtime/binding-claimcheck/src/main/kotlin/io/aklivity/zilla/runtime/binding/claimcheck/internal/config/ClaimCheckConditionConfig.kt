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
                        // Normalize configured path
                        val normalizedPath = if (path.startsWith("/")) path else "/$path"
                        // Handle {uuid} placeholder
                        if (normalizedPath.contains("{uuid}")) {
                            val prefix = normalizedPath.substringBefore("{uuid}")
                            if (value.startsWith(prefix) && value.length > prefix.length) {
                                hasPath = true
                            }
                        } else if (value.startsWith(normalizedPath)) {
                            hasPath = true
                        }
                    }
                    ":method" -> {
                        if (method != null && value.equals(method, ignoreCase = true)) {
                            hasMethod = true
                        }
                    }
                }
            }

            println("ClaimCheckConditionConfig: Matching route (path=$path, method=$method): hasPath=$hasPath, hasMethod=$hasMethod")
            return hasPath && hasMethod
        } catch (ex: Exception) {
            println("ClaimCheckConditionConfig: Error matching route (path=$path, method=$method): ${ex.message}")
            ex.printStackTrace()
            return false
        }
    }
}
