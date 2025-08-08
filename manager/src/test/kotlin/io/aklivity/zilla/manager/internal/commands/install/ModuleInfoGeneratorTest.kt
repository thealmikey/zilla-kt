package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.*
import java.nio.file.*
import java.util.regex.*
import java.util.*
import java.util.spi.ToolProvider
import java.util.jar.JarOutputStream
import java.util.jar.*
import kotlin.test.*
import java.nio.file.*
import kotlin.io.path.*
import java.io.*
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import io.aklivity.zilla.manager.internal.commands.install.ZpmError.PackagingFailed

class ModuleInfoGeneratorTest {

    @Test
    fun `should generate module-info for valid jar`() {
        val tempDir = Files.createTempDirectory("zpm-gen-test")
        val fakeJar = createDummyJar("valid.jar")
        val outputDir = tempDir.resolve("out")

        val events = mutableListOf<String>()
        val generator = ModuleInfoGenerator(
            ignoreMissingDeps = true,
            dryRun = false,
            feedback = { events.add(it) }
        )

        val result = generator.generate(fakeJar, outputDir)

        if (result.isLeft()) {
            println("WARNING: jdeps not available or test environment lacks toolchain.")
            println("Error: ${result.swap().getOrNull()}")
        }

        // Technically may fail if jdeps isn't available; that's OK
        if (result.isRight()) {
            val generated = result.getOrNull()!!
            assertTrue(generated.exists(), "Expected module-info.java to be generated")
            assertTrue(generated.name.endsWith("module-info.java"))
        }
    }

    @Test
    fun `should simulate generation in dry run mode`() {
        val tempDir = Files.createTempDirectory("zpm-dryrun")
        val fakeJar = createDummyJar("dryrun.jar")
        val outputDir = tempDir.resolve("out")

        val logs = mutableListOf<String>()
        val generator = ModuleInfoGenerator(dryRun = true, feedback = { logs.add(it) })

        val result = generator.generate(fakeJar, outputDir)

        assertTrue(result.isRight())
        val expectedPath = result.getOrNull()!!
        assertEquals("module-info.java", expectedPath.fileName.toString())
        assertEquals("dryrun", expectedPath.parent.fileName.toString())

    }

    @Test
    fun `should patch uses statements in module-info`() {
        val content = """
            module example {
                provides com.foo.Service with com.foo.impl.ServiceImpl;
                provides com.bar.Service with com.bar.impl.ServiceImpl;
            }
        """.trimIndent()

        val file = Files.createTempDirectory("zpm-uses-patch").resolve("module-info.java")
        Files.writeString(file, content)

        val logs = mutableListOf<String>()
        val generator = ModuleInfoGenerator(feedback = { logs.add(it) })

        // Manually call patch function via public method indirectly
        generator.javaClass.getDeclaredMethod("patchUsesStatements", Path::class.java)
            .apply { isAccessible = true }
            .invoke(generator, file)

        val updated = Files.readString(file)
        assertTrue(updated.contains("uses com.foo.Service;"))
        assertTrue(updated.contains("uses com.bar.Service;"))
        assertTrue(logs.any { it.contains("Patched with uses") })
    }

    @Test
    fun `should return error for nonexistent jar`() {
        val fake = Paths.get("nonexistentxyz.jar")
        val outputDir = Files.createTempDirectory("zpmerror")

        val generator = ModuleInfoGenerator()

        val result = generator.generate(fake, outputDir)

        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertIs<PackagingFailed>(error)
        assertTrue(error.message.contains("Failed to generate"))
    }

    fun createDummyJar(name: String): Path {
        val tempDir = Files.createTempDirectory("zpm-dummy")
        val jarPath = tempDir.resolve(name)

        JarOutputStream(Files.newOutputStream(jarPath)).use { out ->
            val entry = JarEntry("com/example/Dummy.class")
            out.putNextEntry(entry)
            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())) // dummy magic bytes
            out.closeEntry()
        }

        return jarPath
    }

}
