package io.aklivity.zilla.manager.internal.utils

import arrow.core.Either
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import java.nio.file.Path
import java.util.jar.JarFile

enum class JarType { EMPTY, RESOURCE_ONLY, VALID }

fun classifyJar(jarPath: Path): Either<ZpmError, JarType> = Either.catch {
    JarFile(jarPath.toFile()).use { jar ->
        val hasClasses = jar.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".class") }
        val hasResources = jar.entries().asSequence().any { !it.isDirectory && !it.name.endsWith(".class") }
        when {
            !hasClasses && !hasResources -> JarType.EMPTY
            !hasClasses -> JarType.RESOURCE_ONLY
            else -> JarType.VALID
        }
    }
}.mapLeft { err ->
    ZpmError.PackagingFailed("Invalid JAR $jarPath: ${err.message}")
}

enum class JarCopyMode {
    RAW_COPY,   // copying jars intact (keep module-info.class)
    MERGE       // merging jars into a synthetic module (strip module-info.class)
}

object JarEntryFilter {
    private val excludedSuffixes = listOf(".SF", ".RSA", ".DSA", "package-info.class")
    private val excludedExact = setOf(
        "module-info.class",
        "META-INF/MANIFEST.MF",
        "META-INF/INDEX.LIST"
    )
    private val excludedPrefixes = listOf(
        "META-INF/versions/",       // multi-release content
        "META-INF/maven/",          // maven metadata
        "META-INF/native-image/"    // GraalVM configs
    )

    /**
     * Returns true if the entry should be copied into a merged JAR,
     * false if it should be skipped.
     */
    fun shouldCopy(entryName: String, mode: JarCopyMode): Boolean {
        // Always skip signatures
        if (entryName.endsWith(".SF") || entryName.endsWith(".RSA") || entryName.endsWith(".DSA")) return false
        if (entryName == "META-INF/MANIFEST.MF" || entryName == "META-INF/INDEX.LIST") return false

        // Always skip junk META-INF except services
        if (entryName.startsWith("META-INF/") && !entryName.startsWith("META-INF/services/")) {
            // Special case: in RAW_COPY, we still must skip META-INF/versions/**
            if (mode == JarCopyMode.RAW_COPY && entryName.startsWith("META-INF/versions/")) {
                return false
            }
            // In MERGE mode we skip all META-INF except services anyway
            return false
        }

        // MERGE mode: strip module-info
        if (mode == JarCopyMode.MERGE && entryName == "module-info.class") {
            return false
        }

        return true
    }

}
