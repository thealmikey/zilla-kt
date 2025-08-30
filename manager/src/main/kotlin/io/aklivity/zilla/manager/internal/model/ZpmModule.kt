package io.aklivity.zilla.manager.internal.commands.install.cache

import java.nio.file.Path
import java.lang.module.ModuleDescriptor
import java.util.jar.JarFile

data class ZpmModuleKt(
    val name: String? = DELEGATE_NAME,
    val id: ZpmArtifactIdKt? = DELEGATE_ID,
    val paths: MutableSet<Path> = mutableSetOf(),
    val depends: MutableSet<ZpmArtifactIdKt> = mutableSetOf(),
    val automatic: Boolean = false,
    var delegating: Boolean = false
) {
    companion object {
        const val DELEGATE_NAME = "io.aklivity.zilla.manager.delegate"
        val DELEGATE_ID: ZpmArtifactIdKt? = null

        fun hasAnyModuleInfo(jarPath: Path): Boolean {
            return try {
                JarFile(jarPath.toFile()).use { jar ->
                    jar.entries().asSequence().any { entry ->
                        val name = entry.name
                        !entry.isDirectory && (
                                name == "module-info.class" ||
                                        (name.startsWith("META-INF/versions/") && name.endsWith("module-info.class"))
                                )
                    }
                }
            } catch (_: Exception) {
                false
            }
        }
    }






    constructor(artifact: ZpmArtifactKt) : this(
        name = null,
        id = artifact.id,
        paths = mutableSetOf(artifact.path),
        depends = artifact.dependencies
        .mapNotNull { req ->
            if (setOf("java.", "jdk.").any { req.toString().startsWith(it) }) null
            else req
        }.toMutableSet(), // Unnamed modules have no JPMS dependencies
        automatic = false,
        delegating = true
    )

    constructor(descriptor: ModuleDescriptor, artifact: ZpmArtifactKt) : this(
        name = descriptor.name(),
        id = artifact.id.copy(moduleName = descriptor.name()),
        paths = mutableSetOf(artifact.path),
        depends = artifact.dependencies
            .mapNotNull { req ->
                if (setOf("java.", "jdk.").any { req.toString().startsWith(it) }) null
                else req
            }.toMutableSet(),
        automatic = if (hasAnyModuleInfo(artifact.path)) {
            false // treat multi-release module-info as a real module
        } else {
            descriptor.isAutomatic()
        },
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