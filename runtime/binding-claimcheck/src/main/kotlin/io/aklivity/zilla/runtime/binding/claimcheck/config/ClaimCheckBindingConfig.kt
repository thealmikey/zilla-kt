package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.engine.config.BindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.config.*

data class ClaimCheckBindingConfig(
    val id: Long,
    val name: String,
    val kind: String,
    val options: ClaimCheckOptionsConfig,
    val routes: List<ClaimCheckRouteConfig>
) {
    constructor(binding: BindingConfig) : this(
        id = binding.id,
        name = binding.name,
        kind = binding.kind.name,
        options = binding.options as ClaimCheckOptionsConfig,
        routes = binding.routes.map { ClaimCheckRouteConfig(it) }
    ) {
        println("ClaimCheckBindingConfig: Constructing binding with id=$id, name=$name, routes=${routes.map { it.id }}")
    }

    fun resolve(
        authorization: Long,
        beginEx: io.aklivity.zilla.runtime.binding.claimcheck.internal.types.stream.HttpBeginExFW
    ): ClaimCheckRouteConfig? {
        println("ClaimCheckBindingConfig: Entering resolve(bindingId=$id, authorization=$authorization)")
        try {
            println("ClaimCheckBindingConfig: Number of routes: ${routes.size}")
            routes.forEachIndexed { index, route ->
                println("ClaimCheckBindingConfig: Evaluating route[$index] with id=${route.id}, whenConditions=${route.whenConditions.size}")
                route.whenConditions.forEach { condition ->
                    println("ClaimCheckBindingConfig: Checking condition: $condition")
                    val matches = condition.matches(beginEx)
                    println("ClaimCheckBindingConfig: Condition match result: $matches")
                }
            }
            beginEx.headers().forEach { header ->
                println("ClaimCheckBindingConfig: Header: ${header.name().asString()}=${header.value().asString()}")
            }
            val matchedRoute = routes.firstOrNull { route ->
                route.whenConditions.any { cond ->
                    cond.matches(beginEx).also { matches ->
                        println("ClaimCheckBindingConfig: Route id=${route.id}, condition match: $matches")
                    }
                }
            }
            if (matchedRoute == null) {
                println("ClaimCheckBindingConfig: No route matched for bindingId=$id")
            } else {
                println("ClaimCheckBindingConfig: Matched route id=${matchedRoute.id}")
            }
            println("ClaimCheckBindingConfig: Exiting resolve")
            return matchedRoute
        } catch (e: Exception) {
            println("ClaimCheckBindingConfig: Error in resolve for bindingId=$id")
            e.printStackTrace()
            return null
        }
    }
}