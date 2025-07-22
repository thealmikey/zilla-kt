package io.aklivity.zilla.manager.internal.commands.install

import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultModuleScanner
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import org.junit.jupiter.api.Test
import java.nio.file.*
import kotlin.test.*

class ModuleScannerTest {

    private val feedback = mutableListOf<String>()

    @Test
    fun `should find jar files in valid folder`() {
        val tempDir = Files.createTempDirectory("zpm-scan").apply {
            Files.createFile(resolve("a.jar"))
            Files.createFile(resolve("b.txt"))
            Files.createFile(resolve("c.jar"))
        }

        val scanner = DefaultModuleScanner(feedback = feedback::add)
        val result = scanner.scan(tempDir)

        assertTrue(result.isRight())
        assertEquals(2, result.getOrNull()?.size)
        assertTrue(feedback.any { it.contains("Found 2") })
    }

    @Test
    fun `should fail on non existent path`() {
        val fake = Paths.get("/path/does/not/exist")
        val scanner = DefaultModuleScanner(feedback = feedback::add)

        val result = scanner.scan(fake)

        assertTrue(result.isLeft())
        val err = result.swap().getOrNull()
        assertTrue(err is ZpmError.ModuleScanFailed)
        assertTrue(feedback.any { it.contains("❌ Directory does not exist") })
    }

    @Test
    fun `should fail when scanning file instead of directory`() {
        val file = Files.createTempFile("zpm-file", ".tmp")
        val scanner = DefaultModuleScanner(feedback = feedback::add)

        val result = scanner.scan(file)

        assertTrue(result.isLeft())
        assertTrue(feedback.any { it.contains("not a directory") })
    }

    @Test
    fun `should simulate scan in dry run mode`() {
        val dir = Files.createTempDirectory("zpm-dry")
        val scanner = DefaultModuleScanner(dryRun = true, feedback = feedback::add)

        val result = scanner.scan(dir)

        assertTrue(result.isRight())
        assertEquals(0, result.getOrNull()?.size)
        assertTrue(feedback.any { it.contains("[dry-run] Would scan") })
    }
}
