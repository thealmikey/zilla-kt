package io.aklivity.zilla.runtime.binding.claimcheck.internal.config

import io.aklivity.zilla.runtime.engine.config.BindingConfig
import io.aklivity.zilla.runtime.binding.claimcheck.config.*
import io.aklivity.zilla.runtime.binding.http.internal.types.stream.HttpBeginExFW

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
    )

    fun resolve(
        authorization: Long,
        beginEx: HttpBeginExFW
    ): ClaimCheckRouteConfig? {
        return routes.firstOrNull { route ->
            route.whenConditions.any { cond -> cond.matches(beginEx) }
        }
    }
}

