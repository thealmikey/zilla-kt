package io.aklivity.zilla.runtime.binding.claimcheck.config

import io.aklivity.zilla.runtime.binding.claimcheck.internal.config.ClaimCheckConditionConfig
import io.aklivity.zilla.runtime.engine.config.RouteConfig

data class ClaimCheckRouteConfig(
    val id: Long,
    val with: ClaimCheckWithConfig? = null,
    val whenConditions: List<ClaimCheckConditionConfig> = emptyList()
) {
    constructor(route: RouteConfig) : this(
        id = route.id,
        with = route.with as? ClaimCheckWithConfig,
        whenConditions = route.`when`.filterIsInstance<ClaimCheckConditionConfig>()
    )
}
