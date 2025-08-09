package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Either
import arrow.core.Some
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZpmCacheTestKt {
    private lateinit var workspace: Path
    private lateinit var cacheDir: Path

    @BeforeEach
    fun setUp() {
        workspace = Files.createTempDirectory("zpm-test")
        cacheDir = workspace.resolve("cache").createDirectories()
    }

    @AfterEach
    fun tearDown() {
        Files.walk(workspace)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `should resolve single import`() {
        val cache = ZpmCacheKt(repositories = emptyList(), localCacheDir = cacheDir)
        val import = listOf(ZpmDependencyKt("io.aklivity", "zilla-core", Some("0.9.0")))
        val dependencies = emptyList<ZpmDependencyKt>()
        val templatePath = workspace.resolve("zpm.json")
        Files.writeString(
            templatePath,
            Json.encodeToString(
                ZpmTemplate.serializer(),
                ZpmTemplate(
                    repositories = emptyList(),
                    imports = listOf("io.aklivity:zilla-core:0.9.0"),
                    dependencies = emptyList()
                )
            )
        )

        val result = cache.resolveImports(import, dependencies)

        assertTrue(result.isRight(), "resolveImports should succeed, but got: ${result.leftOrNull()}")
        val artifacts = result.getOrNull()!!
        assertEquals(1, artifacts.size, "Should resolve one artifact")
        assertTrue(
            artifacts.any { it.id == ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0") },
            "Should contain zilla-core:0.9.0"
        )
        assertTrue(
            artifacts.any { it.path.toString().contains("zilla-core-0.9.0.jar") },
            "Artifact path should contain zilla-core-0.9.0.jar"
        )
    }

    @Test
    fun `should resolve import with dependency`() {
        val cache = ZpmCacheKt(repositories = emptyList(), localCacheDir = cacheDir)
        val import = listOf(ZpmDependencyKt("io.aklivity", "zilla-core", Some("0.9.0")))
        val dependencies = listOf(ZpmDependencyKt("io.aklivity", "zilla-binding-mqtt", Some("0.9.0")))
        val templatePath = workspace.resolve("zpm.json")
        Files.writeString(
            templatePath,
            Json.encodeToString(
                ZpmTemplate.serializer(),
                ZpmTemplate(
                    repositories = emptyList(),
                    imports = listOf("io.aklivity:zilla-core:0.9.0"),
                    dependencies = listOf("io.aklivity:zilla-binding-mqtt:0.9.0")
                )
            )
        )

        val result = cache.resolveImports(import, dependencies)

        assertTrue(result.isRight(), "resolveImports should succeed, but got: ${result.leftOrNull()}")
        val artifacts = result.getOrNull()!!
        assertEquals(2, artifacts.size, "Should resolve two artifacts")
        assertTrue(
            artifacts.any { it.id == ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0") },
            "Should contain zilla-core:0.9.0"
        )
        assertTrue(
            artifacts.any { it.id == ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0") },
            "Should contain zilla-binding-mqtt:0.9.0"
        )
    }

    @Test
    fun `should fail on missing artifact`() {
        val cache = ZpmCacheKt(repositories = emptyList(), localCacheDir = cacheDir)
        val import = listOf(ZpmDependencyKt("io.aklivity", "zilla-missing", Some("0.9.0")))
        val dependencies = emptyList<ZpmDependencyKt>()
        val templatePath = workspace.resolve("zpm.json")
        Files.writeString(
            templatePath,
            Json.encodeToString(
                ZpmTemplate.serializer(),
                ZpmTemplate(
                    repositories = emptyList(),
                    imports = listOf("io.aklivity:zilla-missing:0.9.0"),
                    dependencies = emptyList()
                )
            )
        )

        val result = cache.resolveImports(import, dependencies)

        assertTrue(result.isLeft(), "resolveImports should fail for missing artifact")
        val error = result.leftOrNull()
        assertTrue(error is ZpmResolutionErrorKt.ArtifactNotFound, "Should return ArtifactNotFound, got: $error")
    }

    @Test
    fun `should handle snapshot version`() {
        val cache = ZpmCacheKt(repositories = emptyList(), localCacheDir = cacheDir)
        val import = listOf(ZpmDependencyKt("io.aklivity", "zilla-core", Some("0.9.0-SNAPSHOT")))
        val dependencies = emptyList<ZpmDependencyKt>()
        val templatePath = workspace.resolve("zpm.json")
        Files.writeString(
            templatePath,
            Json.encodeToString(
                ZpmTemplate.serializer(),
                ZpmTemplate(
                    repositories = emptyList(),
                    imports = listOf("io.aklivity:zilla-core:0.9.0-SNAPSHOT"),
                    dependencies = emptyList()
                )
            )
        )

        val result = cache.resolveImports(import, dependencies)

        assertTrue(result.isRight(), "resolveImports should succeed for snapshot, but got: ${result.leftOrNull()}")
        val artifacts = result.getOrNull()!!
        assertEquals(1, artifacts.size, "Should resolve one artifact")
        assertTrue(
            artifacts.any { it.id == ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0-SNAPSHOT") },
            "Should contain zilla-core:0.9.0-SNAPSHOT"
        )
        assertTrue(
            artifacts.any { it.path.toString().contains("zilla-core-0.9.0-SNAPSHOT.jar") },
            "Artifact path should contain zilla-core-0.9.0-SNAPSHOT.jar"
        )
    }
}