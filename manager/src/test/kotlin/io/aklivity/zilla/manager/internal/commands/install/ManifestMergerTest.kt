package io.aklivity.zilla.manager.internal.commands.install.impl

import org.junit.jupiter.api.*
import java.nio.file.*
import java.util.jar.*
import kotlin.test.*

class ManifestMergerTest {

    private fun createJarWithManifest(path: Path, attributes: Map<String, String>) {
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            attributes.forEach { (k, v) -> mainAttributes.put(Attributes.Name(k), v) }
        }

        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path), manifest).use { /* just create */ }
    }

    @Test
    fun `should merge manifest entries`() {
        val tempDir = Files.createTempDirectory("zpm-manifest")
        val jar1 = tempDir.resolve("one.jar")
        val jar2 = tempDir.resolve("two.jar")
        val output = tempDir.resolve("merged.mf")

        createJarWithManifest(jar1, mapOf("Implementation-Title" to "One"))
        createJarWithManifest(jar2, mapOf("Implementation-Version" to "2.0"))

        val logs = mutableListOf<String>()
        val merger = ManifestMerger(feedback = { logs.add(it) })

        val result = merger.merge(listOf(jar1, jar2), output)
        assertTrue(result.isRight())
        assertTrue(Files.exists(output))

        val manifest = Manifest(Files.newInputStream(output))
        val attrs = manifest.mainAttributes

        assertEquals("1.0", attrs.getValue("Manifest-Version"))
        assertEquals("One", attrs.getValue("Implementation-Title"))
        assertEquals("2.0", attrs.getValue("Implementation-Version"))
    }

    @Test
    fun `should simulate merge in dry run mode`() {
        val tempDir = Files.createTempDirectory("zpm-dryrun")
        val jar = tempDir.resolve("dry.jar")
        val output = tempDir.resolve("output.mf")

        createJarWithManifest(jar, mapOf("Foo" to "Bar"))

        val logs = mutableListOf<String>()
        val merger = ManifestMerger(dryRun = true, feedback = { logs.add(it) })

        val result = merger.merge(listOf(jar), output)
        assertTrue(result.isRight())
        assertTrue(logs.any { it.contains("[dry-run]") })
        assertFalse(Files.exists(output)) // Should not exist in dry-run
    }
}
