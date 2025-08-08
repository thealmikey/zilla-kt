package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Some
import arrow.core.getOrElse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledIf
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteRecursively
import org.junit.jupiter.api.Assertions.*
import kotlin.io.path.ExperimentalPathApi

class ZpmCacheKtTest {
    private val logger = LoggerFactory.getLogger(ZpmCacheKtTest::class.java)
    private lateinit var tempDir: Path
    private val localRepoPath = Paths.get(System.getProperty("user.home"), ".m2/repository")

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("zpm-cache-test")
        logger.info("Created temporary directory: {}", tempDir)
        // Check for required artifacts
        val artifacts = listOf(
            "io/aklivity/zilla/runtime/develop-SNAPSHOT/runtime-develop-SNAPSHOT.pom",
            "io/aklivity/zilla/runtime/develop-SNAPSHOT/runtime-develop-SNAPSHOT.jar",
            "io/aklivity/zilla/incubator/develop-SNAPSHOT/incubator-develop-SNAPSHOT.pom",
            "io/aklivity/zilla/incubator/develop-SNAPSHOT/incubator-develop-SNAPSHOT.jar"
        )
        artifacts.forEach { artifactPath ->
            val path = localRepoPath.resolve(artifactPath)
            if (!Files.exists(path)) {
                throw IllegalStateException(
                    "Local artifact $artifactPath not found in $localRepoPath. " +
                            "Run 'mvn clean install -DskipTests' in ~/projects/2zilla."
                )
            }
        }
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterEach
    fun tearDown() {
        tempDir.deleteRecursively()
        logger.info("Deleted temporary directory: {}", tempDir)
    }

    @Test
    @DisabledIf("artifactsMissing")
    fun `should return empty list when no dependencies provided`() {
        val cache = ZpmCacheKt(
            repositories = emptyList(),
            localCacheDir = localRepoPath
        )

        val result = cache.resolve(emptyList(), emptyList())

        assertTrue(result.isRight()) { "Expected Right result for empty dependencies" }
        val resolved = result.getOrElse { emptyList() }
        assertTrue(resolved.isEmpty()) { "Expected empty list for no dependencies, got $resolved" }
    }

    @Test
    @DisabledIf("artifactsMissing")
    fun `should resolve managed dependencies from local repository`() {
        val cache = ZpmCacheKt(
            repositories = emptyList(),
            localCacheDir = localRepoPath
        )

        val import = ZpmDependencyKt(
            groupId = "io.aklivity.zilla",
            artifactId = "runtime",
            version = Some("develop-SNAPSHOT")
        )

        val result = cache.resolveImports(listOf(import))

        assertTrue(result.isRight()) { "Expected Right result for local dependency, got $result" }
        val dependencyMap = result.getOrElse { emptyMap() }
        assertTrue(dependencyMap.isNotEmpty()) { "Expected non-empty resolved dependency map" }

        dependencyMap.forEach { (id, artifact) ->
            logger.info("Resolved: {} to {}", id, artifact.path)
            assertTrue(artifact.path.toFile().exists()) { "Resolved artifact path does not exist: ${artifact.path}" }
            assertTrue(artifact.path.startsWith(localRepoPath)) { "Expected artifact path to be in local repository: ${artifact.path}" }
            assertNotNull(artifact.dependencies)
        }
    }

    @Test
    @DisabledIf("artifactsMissing")
    fun `should resolve multiple SNAPSHOT dependencies from local repository`() {
        val cache = ZpmCacheKt(
            repositories = emptyList(),
            localCacheDir = localRepoPath
        )

        val imports = listOf(
            ZpmDependencyKt(
                groupId = "io.aklivity.zilla",
                artifactId = "runtime",
                version = Some("develop-SNAPSHOT")
            ),
            ZpmDependencyKt(
                groupId = "io.aklivity.zilla",
                artifactId = "incubator",
                version = Some("develop-SNAPSHOT")
            )
        )

        val result = cache.resolveImports(imports)

        assertTrue(result.isRight()) { "Expected Right result for local SNAPSHOT dependencies, got $result" }
        val dependencyMap = result.getOrElse { emptyMap() }
        assertEquals(imports.size, dependencyMap.size) { "Expected ${imports.size} resolved artifacts, got ${dependencyMap.size}" }

        dependencyMap.forEach { (id, artifact) ->
            logger.info("Resolved: {} to {}", id, artifact.path)
            assertTrue(artifact.path.toFile().exists()) { "Resolved artifact path does not exist: ${artifact.path}" }
            assertTrue(artifact.path.startsWith(localRepoPath)) { "Expected artifact path to be in local repository: ${artifact.path}" }
            assertNotNull(artifact.dependencies)
        }
    }

    @Test
    fun `should handle invalid dependency gracefully`() {
        val cache = ZpmCacheKt(
            repositories = emptyList(),
            localCacheDir = localRepoPath
        )

        val invalidImport = ZpmDependencyKt(
            groupId = "io.aklivity.zilla",
            artifactId = "nonexistent-artifact",
            version = Some("0.0.0")
        )

        val result = cache.resolveImports(listOf(invalidImport))

        assertTrue(result.isLeft()) { "Expected Left result for invalid dependency, got $result" }
        result.fold(
            { error -> assertTrue(error is ZpmResolutionErrorKt.DependencyResolutionError) { "Expected DependencyResolutionError, got $error" } },
            { fail("Expected Left result, got Right") }
        )
    }

    companion object {
        @JvmStatic
        fun artifactsMissing(): Boolean {
            val localRepoPath = Paths.get(System.getProperty("user.home"), ".m2/repository")
            val artifacts = listOf(
                "io/aklivity/zilla/runtime/develop-SNAPSHOT/runtime-develop-SNAPSHOT.pom",
                "io/aklivity/zilla/runtime/develop-SNAPSHOT/runtime-develop-SNAPSHOT.jar",
                "io/aklivity/zilla/incubator/develop-SNAPSHOT/incubator-develop-SNAPSHOT.pom",
                "io/aklivity/zilla/incubator/develop-SNAPSHOT/incubator-develop-SNAPSHOT.jar"
            )
            return artifacts.any { !Files.exists(localRepoPath.resolve(it)) }
        }
    }
}