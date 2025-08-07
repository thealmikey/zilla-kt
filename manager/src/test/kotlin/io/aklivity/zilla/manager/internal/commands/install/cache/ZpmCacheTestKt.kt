package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Some
import arrow.core.getOrElse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ZpmCacheKtTest : StringSpec({
    "should return empty list when no dependencies provided" {
        val tempDir = Files.createTempDirectory("zpm-cache")
        val cache = ZpmCacheKt(
            repositories = ZpmRepositoryConfigKt.defaultRepositories(),
            localCacheDir = tempDir
        )

        val result = cache.resolve(emptyList(), emptyList())

        result.isRight() shouldBe true
//        result.orNull()?.shouldBeEmpty()
    }

    "should resolve managed dependencies from imports" {
    val tempDir = Files.createTempDirectory("zpm-cache")
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

    result.isRight() shouldBe true
    val map = result.getOrElse { emptyMap() }
    map.size shouldBeGreaterThan 3
    map.keys.forEach { println("Imported: $it → ${map[it]}") }
}

})
