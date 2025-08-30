
package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import io.aklivity.zilla.manager.internal.commands.install.ModuleScanner
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarOutputStream

class DefaultModuleScannerTest {

    private lateinit var scanner: ModuleScanner
    private val feedbackMessages = mutableListOf<String>()

    @BeforeEach
    fun setUp() {
        scanner = DefaultModuleScanner(
            dryRun = false,
            feedback = { msg -> feedbackMessages.add(msg) }
        )
    }

    @Test
    fun `should fail if path does not exist`() {
        val nonExistentPath = Path.of("non-existent-dir")
        val result = scanner.scan(nonExistentPath)

        assertTrue(result.isLeft())
        assertTrue((result as Either.Left).value is ZpmError.ModuleScanFailed)
        assertEquals("❌ Directory does not exist: $nonExistentPath", (result.value as ZpmError.ModuleScanFailed).cause.message)
        assertTrue(feedbackMessages.contains("📂 Scanning path: $nonExistentPath"))
        assertTrue(feedbackMessages.contains("❌ Directory does not exist: $nonExistentPath"))
    }

    @Test
    fun `should fail if path is not a directory`(@TempDir tempDir: Path) {
        val filePath = tempDir.resolve("not-a-dir.txt")
        Files.createFile(filePath)
        val result = scanner.scan(filePath)

        assertTrue(result.isLeft())
        assertTrue((result as Either.Left).value is ZpmError.ModuleScanFailed)
        assertEquals("❌ Path is not a directory: $filePath", (result.value as ZpmError.ModuleScanFailed).cause.message)
        assertTrue(feedbackMessages.contains("📂 Scanning path: $filePath"))
        assertTrue(feedbackMessages.contains("❌ Path is not a directory: $filePath"))
    }

    @Test
    fun `should return empty list for empty directory`(@TempDir tempDir: Path) {
        val result = scanner.scan(tempDir)

        assertTrue(result.isRight())
        assertEquals(emptyList<Path>(), (result as Either.Right).value)
        assertTrue(feedbackMessages.contains("📂 Scanning path: $tempDir"))
        assertTrue(feedbackMessages.contains("✅ Found 0 .jar file(s)"))
    }

    @Test
    fun `should find JAR files in directory`(@TempDir tempDir: Path) {
        val jar1 = createEmptyJar(tempDir.resolve("test1.jar"))
        val jar2 = createEmptyJar(tempDir.resolve("test2.jar"))
        Files.createFile(tempDir.resolve("not-a-jar.txt")) // Non-JAR file
        val result = scanner.scan(tempDir)

        assertTrue(result.isRight())
        val jars = (result as Either.Right).value
        assertEquals(2, jars.size)
        assertTrue(jars.contains(jar1))
        assertTrue(jars.contains(jar2))
        assertTrue(feedbackMessages.contains("📂 Scanning path: $tempDir"))
        assertTrue(feedbackMessages.contains("✅ Found 2 .jar file(s)"))
    }

    @Test
    fun `should handle dry run`(@TempDir tempDir: Path) {
        scanner = DefaultModuleScanner(dryRun = true, feedback = { msg -> feedbackMessages.add(msg) })
        createEmptyJar(tempDir.resolve("test.jar"))
        val result = scanner.scan(tempDir)

        assertTrue(result.isRight())
        assertEquals(emptyList<Path>(), (result as Either.Right).value)
        assertTrue(feedbackMessages.contains("📂 Scanning path: $tempDir"))
        assertTrue(feedbackMessages.contains("✅ [dry-run] Would scan: $tempDir and collect .jar files"))
    }

    @Test
    fun `should handle no feedback`() {
        scanner = DefaultModuleScanner(feedback = null)
        val nonExistentPath = Path.of("non-existent-dir")
        scanner.scan(nonExistentPath) // No exception, just no feedback
    }

    // Helper method to create an empty JAR
    private fun createEmptyJar(path: Path): Path {
        JarOutputStream(Files.newOutputStream(path)).use { /* empty */ }
        return path
    }
}