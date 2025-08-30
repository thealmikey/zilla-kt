package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class DefaultJarExtenderTest {

    private lateinit var extender: DefaultJarExtender
    private val feedbackMessages = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        extender = DefaultJarExtender(
            dryRun = false,
            feedback = { msg -> feedbackMessages.add(msg) }
        )
    }

    @Test
    fun `should fail if source JAR does not exist`() {
        val nonExistentSource = Path.of("non-existent.jar")
        val target = Path.of("target.jar")
        val newEntry = JarEntry("test.txt")
        val newEntryPath = Path.of("dummy.txt")
        val result = extender.extendJarWithRetry(nonExistentSource, target, newEntry, newEntryPath)

        assertTrue(result.isLeft())
        assertTrue(feedbackMessages.contains("❌ Source JAR does not exist or is unreadable: non-existent.jar"))
    }

    @Test
    fun `should extend JAR with new entry`(@TempDir tempDir: Path) {
        val sourceJar = createSimpleJar(tempDir.resolve("source.jar"))
        val targetJar = tempDir.resolve("target.jar")
        val newEntryContent = tempDir.resolve("newEntry.txt")
        Files.writeString(newEntryContent, "Hello, world!")
        val newEntry = JarEntry("META-INF/newEntry.txt")

        val result = extender.extendJarWithRetry(sourceJar, targetJar, newEntry, newEntryContent)

        assertTrue(result.isRight())
        assertTrue(Files.exists(targetJar))
        JarFile(targetJar.toFile()).use { jar ->
            assertNotNull(jar.getEntry("test.class"))
            assertNotNull(jar.getEntry("META-INF/newEntry.txt"))
            val content = jar.getInputStream(jar.getEntry("META-INF/newEntry.txt")).bufferedReader().readText()
            assertEquals("Hello, world!", content)
        }
        assertTrue(feedbackMessages.contains("✅ Extended JAR to $targetJar"))
    }

    @Test
    fun `should handle dry run`(@TempDir tempDir: Path) {
        extender = DefaultJarExtender(dryRun = true, feedback = { msg -> feedbackMessages.add(msg) })
        val sourceJar = createSimpleJar(tempDir.resolve("source.jar"))
        val targetJar = tempDir.resolve("target.jar")
        val newEntry = JarEntry("test.txt")
        val newEntryPath = tempDir.resolve("newEntry.txt")
        Files.writeString(newEntryPath, "content")

        val result = extender.extendJarWithRetry(sourceJar, targetJar, newEntry, newEntryPath)

        assertTrue(result.isRight())
        assertTrue(Files.exists(targetJar))
        assertEquals("// Dry-run extended JAR", Files.readString(targetJar))
        assertTrue(feedbackMessages.contains("🧪 [dry-run] Would extend $sourceJar with test.txt"))
    }

    private fun createSimpleJar(path: Path): Path {
        JarOutputStream(Files.newOutputStream(path)).use { jos ->
            jos.putNextEntry(JarEntry("test.class"))
            jos.write("test".toByteArray())
            jos.closeEntry()
        }
        return path
    }
}