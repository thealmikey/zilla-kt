package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.None
import arrow.core.Some
import java.nio.file.Path
import java.lang.module.ModuleDescriptor

data class ZpmModuleKt(
    val name: String? = DELEGATE_NAME,
    val id: ZpmArtifactIdKt? = DELEGATE_ID,
    val paths: MutableSet<Path> =mutableSetOf<Path>(),
    val depends: MutableSet<ZpmDependencyKt> = mutableSetOf<ZpmDependencyKt>(),
    val automatic: Boolean = false,
    var delegating: Boolean = false
) {
    companion object {
        const val DELEGATE_NAME = "io.aklivity.zilla.manager.delegate"
        val DELEGATE_ID: ZpmArtifactIdKt? = null
    }

    constructor(artifact: ZpmArtifactKt) : this(
        name = null,
        id = artifact.id,
        paths = mutableSetOf(artifact.path),
        depends = artifact.dependencies.map { dep ->
            ZpmDependencyKt(dep.groupId, dep.artifactId, if (dep.version.isNotBlank()) Some(dep.version) else None)
        }.toMutableSet(),
        automatic = false,
        delegating = true
    )

    constructor(descriptor: ModuleDescriptor, artifact: ZpmArtifactKt) : this(
        name = descriptor.name(),
        id = artifact.id,
        paths = LinkedHashSet(setOf(artifact.path)),
        depends = descriptor.requires().mapNotNull { req ->
            if (setOf("java.", "jdk.").any { req.name().startsWith(it) }) null
            else ZpmDependencyKt.fromCoordinates(req.name().replace(".", ":"))
        }.toMutableSet(),
        automatic = descriptor.isAutomatic(),
        delegating = false
    )

    constructor(module: ZpmModuleKt) : this(
        name = module.name,
        id = module.id,
        paths = mutableSetOf<Path>() ,
        depends =  mutableSetOf<ZpmDependencyKt>() ,
        automatic = false,
        delegating = false
    )

    override fun hashCode(): Int {
        return setOf(name, automatic, paths, id, depends, delegating).hashCode()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ZpmModuleKt) return false
        return name == other.name &&
                automatic == other.automatic &&
                paths == other.paths &&
                id == other.id &&
                depends == other.depends &&
                delegating == other.delegating
    }

    override fun toString(): String {
        return "$name${if (automatic) "@" else ""}${if (delegating) "+" else ""} -> $depends $paths"
    }
}