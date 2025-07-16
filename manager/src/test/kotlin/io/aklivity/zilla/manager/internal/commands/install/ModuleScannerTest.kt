package io.aklivity.zilla.manager.internal.commands.install

import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultModuleScanner
import org.junit.jupiter.api.Test
import java.nio.file.*
import kotlin.test.assertTrue
import arrow.core.*

class ModuleScannerTest {

    private val scanner = DefaultModuleScanner()

    @Test
    fun `should find all jar files in folder`() {
        val tempDir = Files.createTempDirectory("zpm-test").apply {
            Files.createFile(resolve("a.jar"))
            Files.createFile(resolve("b.txt"))
            Files.createFile(resolve("c.jar"))
        }

        val result = scanner.scan(tempDir)

        assertTrue(result.isRight(), "Expected scan to succeed")

        val jars = result.getOrNull()
        assertTrue(jars?.size == 2)
        assertTrue(jars!!.all { it.toString().endsWith(".jar") })
    }
}
