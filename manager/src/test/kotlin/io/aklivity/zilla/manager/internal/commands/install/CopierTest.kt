package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import kotlin.test.*
import org.junit.jupiter.api.*
import java.nio.file.*
import java.util.jar.*
import arrow.core.*

class JarCopierTest {

    private fun createJar(path: Path, entries: Map<String, String>) {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { out ->
            entries.forEach { (name, content) ->
                val entry = JarEntry(name)
                out.putNextEntry(entry)
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun `should copy typical dot class entries`() {
        val jar = Files.createTempFile("test-", ".jar")
        val output = Files.createTempFile("out-", ".jar")
        createJar(jar, mapOf("com/example/Test.class" to "dummy"))

        val copier = JarCopier()
        val result = copier.copyJars(listOf(jar), output)

        assertTrue(result.isRight())
        val contents = JarFile(output.toFile()).entries().asSequence().map { it.name }.toList()
        assertTrue("com/example/Test.class" in contents)
    }

    @Test
    fun `should exclude moduleinfo and manifest`() {
        val jar = Files.createTempFile("exclude-", ".jar")
        val output = Files.createTempFile("out-", ".jar")
        createJar(jar, mapOf(
            "module-info.class" to "ignore",
            "META-INF/MANIFEST.MF" to "Manifest",
            "com/example/Keep.class" to "yes"
        ))

        val copier = JarCopier()
        val result = copier.copyJars(listOf(jar), output)

        val contents = JarFile(output.toFile()).entries().asSequence().map { it.name }.toList()
        assertTrue("com/example/Keep.class" in contents)
        assertFalse("module-info.class" in contents)
        assertFalse("META-INF/MANIFEST.MF" in contents)
    }

    // @Test
    // fun `should merge METAINF services`() {
    //     val jar1 = Files.createTempFile("jar1-", ".jar")
    //     val jar2 = Files.createTempFile("jar2-", ".jar")
    //     val output = Files.createTempFile("merged-", ".jar")

    //     createJar(jar1, mapOf(
    //         "META-INF/services/java.sql.Driver" to "com.Driver1"
    //     ))
    //     createJar(jar2, mapOf(
    //         "META-INF/services/java.sql.Driver" to "com.Driver2"
    //     ))

    //     val copier = JarCopier()
    //     val result = copier.copyJars(listOf(jar1, jar2), output)
    //     assertTrue(result.isRight())

    //     val servicesEntry = JarFile(output.toFile())
    //         .getJarEntry("META-INF/services/java.sql.Driver")
    //    // print("This the content in services entry", servicesEntry.toString)
    //    // assertNotNull(servicesEntry)

    //     val merged = JarFile(output.toFile()).getInputStream(servicesEntry).bufferedReader().readLines()
    //     assertTrue(merged.contains("com.Driver1"))
    //     assertTrue(merged.contains("com.Driver2"))
    // }

    @Test
    fun `should simulate dryRun`() {
        val jar = Files.createTempFile("dry-", ".jar")
        val output = Files.createTempFile("dry-out-", ".jar")
        Files.deleteIfExists(output)

        createJar(jar, mapOf("example.class" to "data"))

        val events = mutableListOf<String>()
        val copier = JarCopier(dryRun = true, feedback = { events.add(it) })

        val result = copier.copyJars(listOf(jar), output)
        assertTrue(result.isRight())
        assertTrue(events.any { it.contains("dry-run") })
        assertFalse(Files.exists(output)) // dryRun must not create jar
    }

    @Test
    fun `should return error if input jar is missing`() {
        val missing = Paths.get("nonexistent.jar")
        val output = Files.createTempFile("fail-", ".jar")

        val copier = JarCopier()
        val result = copier.copyJars(listOf(missing), output)

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertIs<ZpmError.PackagingFailed>(error)
    }
}
