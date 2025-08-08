package io.aklivity.zilla.manager.internal.commands.install.model

import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactIdKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactKt
import java.lang.module.ModuleDescriptor
import java.nio.file.Path

data class ZpmModuleKt(
    val name: String? = null,
    val id: ZpmArtifactIdKt? = null,
    val paths: MutableSet<Path> = mutableSetOf(),
    val depends: Set<ZpmArtifactIdKt> = emptySet(),
    var delegating: Boolean = false,
    val automatic: Boolean = false
) {
    constructor(descriptor: ModuleDescriptor, artifact: ZpmArtifactKt) : this(
        name = descriptor.name(),
        id = artifact.id,
        paths = mutableSetOf(artifact.path),
        depends = descriptor.requires().mapNotNull { ZpmArtifactIdKt.parse(it.name()) }.toSet(),
        automatic = descriptor.isAutomatic
    )
    constructor(artifact: ZpmArtifactKt) : this(id = artifact.id, paths = mutableSetOf(artifact.path))
}