package io.aklivity.zilla.manager.internal.commands.install

import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmModuleKt
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class ModuleInfoGeneratorTest {
    private lateinit var workspace: Path
    private lateinit var installDir: Path
    private lateinit var targetJar: Path
    private lateinit var delegate: ZpmModuleKt
    private lateinit var feedbackMessages: MutableList<String>
    private lateinit var feedback: (String) -> Unit

    @BeforeEach
    fun setUp() {
        workspace = Files.createTempDirectory("zpm-install-test")
        installDir = workspace.resolve("install")
        Files.createDirectories(installDir)
        targetJar = installDir.resolve("zilla-install.jar")
        delegate = ZpmModuleKt(
            name = null,
            id = null,
            paths = mutableSetOf(workspace.resolve("test.jar"))
        )
        feedbackMessages = mutableListOf()
        feedback = { feedbackMessages.add(it) }
    }

    @Test
    fun `should generate module-info-java successfully`() {
        // Given
        val generator = ModuleInfoGenerator(dryRun = false, feedback = feedback)

        // When
        val result = generator.generate(targetJar, installDir, delegate)

        // Then
        assertTrue(result.isRight()) { "Expected Right, got $result" }
        val moduleInfoPath = result.getOrNull()!!
        assertTrue(Files.exists(moduleInfoPath)) { "module-info.java should exist at $moduleInfoPath" }
        val content = moduleInfoPath.readText()
        assertTrue(content.contains("module zilla.install")) { "Expected module zilla.install in $content" }
        assertTrue(content.contains("requires java.base")) { "Expected requires java.base in $content" }
        assertTrue(content.contains("requires test")) { "Expected requires test in $content" }
        assertTrue(feedbackMessages.contains("Generated module-info.java at $moduleInfoPath"))
    }

    @Test
    fun `should handle dry-run mode`() {
        // Given
        val generator = ModuleInfoGenerator(dryRun = true, feedback = feedback)

        // When
        val result = generator.generate(targetJar, installDir, delegate)

        // Then
        assertTrue(result.isRight()) { "Expected Right, got $result" }
        val moduleInfoPath = result.getOrNull()!!
        assertTrue(Files.exists(moduleInfoPath)) { "module-info.java should exist at $moduleInfoPath" }
        assertEquals("// Dry-run module-info.java", moduleInfoPath.readText())
        assertTrue(feedbackMessages.contains("[dry-run] Would generate module-info.java at $moduleInfoPath"))
    }

    @Test
    fun `should fail when installDir is not writable`() {
        // Given
        val generator = ModuleInfoGenerator(dryRun = false, feedback = feedback)
        val nonWritableDir = workspace.resolve("non-writable")
        Files.createDirectories(nonWritableDir)
        nonWritableDir.toFile().setReadOnly()

        // When
        val result = generator.generate(targetJar, nonWritableDir, delegate)

        // Then
        assertTrue(result.isLeft()) { "Expected Left, got $result" }
        val error = result.getOrNull() as ZpmResolutionErrorKt
        assertTrue(error.toString().contains("Failed to generate module-info.java"))
        assertTrue(feedbackMessages.any { it.contains("Failed to generate module-info.java") })
    }

    @Test
    fun `should handle empty delegate paths`() {
        // Given
        val generator = ModuleInfoGenerator(dryRun = false, feedback = feedback)
        val emptyDelegate = ZpmModuleKt(name = null, id = null, paths = mutableSetOf())

        // When
        val result = generator.generate(targetJar, installDir, emptyDelegate)

        // Then
        assertTrue(result.isRight()) { "Expected Right, got $result" }
        val moduleInfoPath = result.getOrNull()!!
        assertTrue(Files.exists(moduleInfoPath)) { "module-info.java should exist at $moduleInfoPath" }
        val content = moduleInfoPath.readText()
        assertTrue(content.contains("module zilla.install")) { "Expected module zilla.install in $content" }
        assertTrue(content.contains("requires java.base")) { "Expected requires java.base in $content" }
        assertFalse(content.contains("requires test")) { "Expected no additional requires in $content" }
        assertTrue(feedbackMessages.contains("Generated module-info.java at $moduleInfoPath"))
    }
}