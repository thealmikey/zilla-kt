package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Some
import arrow.core.getOrElse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import org.junit.jupiter.api.Assertions.*
import kotlin.io.path.ExperimentalPathApi

class ZpmCacheKtTest {
    private val logger = LoggerFactory.getLogger(ZpmCacheKtTest::class.java)
    private lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("zpm-cache-test")
        logger.info("Created temporary directory: {}", tempDir)
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
        logger.info("Deleted temporary directory: {}", tempDir)
    }

    @Test
    fun `should return empty list when no dependencies provided`() {
        val cache = ZpmCacheKt(
            repositories = ZpmRepositoryConfigKt.defaultRepositories(),
            localCacheDir = tempDir
        )

        val result = cache.resolve(emptyList(), emptyList())

        assertTrue(result.isRight()) { "Expected Right result for empty dependencies" }
        val resolved = result.getOrElse { emptyList() }
        assertTrue(resolved.isEmpty()) { "Expected empty list for no dependencies, got $resolved" }
    }

    @Test
    fun `should resolve managed dependencies from imports`() {
        val cache = ZpmCacheKt(
            repositories = ZpmRepositoryConfigKt.defaultRepositories(),
            localCacheDir = tempDir
        )

        val import = ZpmDependencyKt(
            groupId = "io.aklivity.zilla",
            artifactId = "runtime",
            version = Some("0.9.1")
        )

        val result = cache.resolveImports(listOf(import))

        assertTrue(result.isRight()) { "Expected Right result for valid import, got $result" }
        val dependencyMap = result.getOrElse { emptyMap() }
        assertTrue(dependencyMap.isNotEmpty()) { "Expected non-empty resolved dependency map" }

        dependencyMap.forEach { (id, artifact) ->
            logger.info("Resolved: {} to {}", id, artifact.path)
            assertTrue(artifact.path.toFile().exists()) { "Resolved artifact path does not exist: ${artifact.path}" }
            assertNotNull(artifact.dependencies)
        }
    }

    @Test
    fun `should handle invalid dependency gracefully`() {
        val cache = ZpmCacheKt(
            repositories = ZpmRepositoryConfigKt.defaultRepositories(),
            localCacheDir = tempDir
        )

        val invalidImport = ZpmDependencyKt(
            groupId = "io.aklivity.zilla",
            artifactId = "nonexistent-artifact",
            version = Some("0.0.0")
        )

        val result = cache.resolveImports(listOf(invalidImport))

        assertTrue(result.isLeft()) { "Expected Left result for invalid dependency, got $result" }
        val error = result.getOrNull()
        assertTrue(error is ZpmResolutionErrorKt.DependencyResolutionError)
    }
}
