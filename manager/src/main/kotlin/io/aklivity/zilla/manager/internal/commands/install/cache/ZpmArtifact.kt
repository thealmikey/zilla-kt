package io.aklivity.zilla.manager.internal.commands.install.cache

import java.nio.file.Path

data class ZpmArtifactKt(
    val id: ZpmArtifactIdKt,
    val path: Path,
    val dependencies: Set<ZpmArtifactId>
)
