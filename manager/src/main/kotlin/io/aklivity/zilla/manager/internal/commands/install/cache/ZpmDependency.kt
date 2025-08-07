package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Option
import arrow.core.getOrElse

data class ZpmDependencyKt(
    val groupId: String,
    val artifactId: String,
    val version: Option<String>
) {
    override fun toString(): String = "$groupId:$artifactId:${version.getOrElse{'?'}}"
}
