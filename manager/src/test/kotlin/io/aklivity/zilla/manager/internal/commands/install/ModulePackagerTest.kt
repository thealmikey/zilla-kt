package io.aklivity.zilla.manager.internal.commands.install.impl

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import arrow.core.left
import arrow.core.right
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import io.aklivity.zilla.manager.internal.utils.JarType
import org.junit.jupiter.api.condition.DisabledIfSystemProperty

class DefaultModulePackagerTest {
    private lateinit var tempDir: Path
    private val feedback = mutableListOf<String>()

    private fun createTestJar(path: Path, type: JarType, extraEntries: Map<String, String> = emptyMap()): Path {
        Files.createDirectories(path.parent)
        JarOutputStream(Files.newOutputStream(path)).use { jar ->
            val addedEntries = mutableSetOf<String>()
            fun addEntry(name: String, content: ByteArray) {
                val normalizedName = name.replace('\\', '/')
                if (addedEntries.add(normalizedName)) {
                    jar.putNextEntry(JarEntry(normalizedName))
                    jar.write(content)
                    jar.closeEntry()
                }
            }

            when (type) {
                JarType.EMPTY -> {}
                JarType.RESOURCE_ONLY -> {
                    addEntry("META-INF/services/com.example.Service", "com.example.Impl1\ncom.example.Impl2\n".toByteArray())
                }
                JarType.VALID -> {
                    addEntry("com/example/Test.class", byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
                    addEntry("META-INF/services/com.example.Service", "com.example.Impl1\n".toByteArray())
                }
            }
            extraEntries.forEach { (name, content) ->
                addEntry(name, content.toByteArray())
            }
        }
        return path
    }

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("test")
        feedback.clear()
    }

    @AfterEach
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

//    @Test
//    fun `should package valid JARs with classes and services`() {
//        val jar1 = createTestJar(tempDir.resolve("test1.jar"), JarType.VALID)
//        val jar2 = createTestJar(tempDir.resolve("test2.jar"), JarType.VALID, mapOf("META-INF/services/com.example.Service" to "com.example.Impl2\n"))
//        val output = tempDir.resolve("output.jar")
//        val packager = DefaultModulePackager(feedback = { feedback.add(it) })
//
//        val result = packager.packageModule(listOf(jar1, jar2), output)
//
//        assertEquals(output.right(), result)
//        JarFile(output.toFile()).use { jar ->
//            val entries = jar.entries().asSequence().map { it.name.replace('\\', '/') }.toSet()
//            assertEquals(setOf("com/example/Test.class", "META-INF/services/com.example.Service"), entries)
//            val serviceEntry = jar.getEntry("META-INF/services/com.example.Service")
//            assertNotNull(serviceEntry, "Service entry should exist")
//            val serviceContent = jar.getInputStream(serviceEntry).bufferedReader().readText().trim()
//            assertEquals("com.example.Impl1\ncom.example.Impl2", serviceContent)
//        }
//        println("Actual feedback: $feedback") // Debug output
//        assertEquals(
//            listOf(
//                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
//                "⚠️ Processing valid JAR: ${jar1.toString().replace('\\', '/')}",
//                "⚠️ Processing valid JAR: ${jar2.toString().replace('\\', '/')}",
//                "⚠️ Skipped duplicate entry com/example/Test.class from ${jar2.toString().replace('\\', '/')}",
//                "✅ Merged service com.example.Service with 2 implementations",
//                "✅ Packaged 2 jars into ${output.toString().replace('\\', '/')}"
//            ),
//            feedback
//        )
//    }

    @Test
    fun `should package resource-only JARs`() {
        val jar = createTestJar(tempDir.resolve("resource.jar"), JarType.RESOURCE_ONLY)
        val output = tempDir.resolve("output.jar")
        val packager = DefaultModulePackager(feedback = { feedback.add(it) })

        val result = packager.packageModule(listOf(jar), output)

        assertEquals(output.right(), result)
        JarFile(output.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name.replace('\\', '/') }.toSet()
            assertEquals(setOf("META-INF/services/com.example.Service"), entries)
            val serviceEntry = jar.getEntry("META-INF/services/com.example.Service")
            assertNotNull(serviceEntry, "Service entry should exist")
            val serviceContent = jar.getInputStream(serviceEntry).bufferedReader().readText().trim()
            assertEquals("com.example.Impl1\ncom.example.Impl2", serviceContent)
        }
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "⚠️ Processing resource-only JAR: ${jar.toString().replace('\\', '/')}",
                "✅ Merged service com.example.Service with 2 implementations",
                "✅ Packaged 1 jars into ${output.toString().replace('\\', '/')}"
            ),
            feedback
        )
    }

    @Test
    fun `should fail if all JARs are empty`() {
        val jar = createTestJar(tempDir.resolve("empty.jar"), JarType.EMPTY)
        val output = tempDir.resolve("output.jar")
        val packager = DefaultModulePackager(feedback = { feedback.add(it) })

        val result = packager.packageModule(listOf(jar), output)

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is ZpmError.PackagingFailed)
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "⚠️ Skipping empty JAR: ${jar.toString().replace('\\', '/')}",
                "❌ No valid or resource-only JARs to package"
            ),
            feedback
        )
    }

    @Test
    fun `should fail on non-existent JAR`() {
        val nonExistent = tempDir.resolve("missing.jar")
        val output = tempDir.resolve("output.jar")
        val packager = DefaultModulePackager(feedback = { feedback.add(it) })

        val result = packager.packageModule(listOf(nonExistent), output)

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is ZpmError.PackagingFailed)
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "⚠️ JAR does not exist: ${nonExistent.toString().replace('\\', '/')}",
                "❌ JAR does not exist: ${nonExistent.toString().replace('\\', '/')}"
            ),
            feedback
        )
    }

    @Test
    @DisabledIfSystemProperty(named = "os.name", matches = ".*[Ww]indows.*", disabledReason = "PosixFilePermissions not supported on Windows")
    fun `should fail on unreadable JAR`() {
        val jar = createTestJar(tempDir.resolve("test.jar"), JarType.VALID)
        try {
            Files.setPosixFilePermissions(jar, emptySet())
            val output = tempDir.resolve("output.jar")
            val packager = DefaultModulePackager(feedback = { feedback.add(it) })

            val result = packager.packageModule(listOf(jar), output)

            assertTrue(result.isLeft())
            assertTrue(result.leftOrNull() is ZpmError.PackagingFailed)
            assertEquals(
                listOf(
                    "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                    "⚠️ JAR not readable: ${jar.toString().replace('\\', '/')}",
                    "❌ JAR not readable: ${jar.toString().replace('\\', '/')}"
                ),
                feedback
            )
        } finally {
            Files.setPosixFilePermissions(jar, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
        }
    }

    @Test
    fun `should simulate packaging in dry-run mode`() {
        val jar = createTestJar(tempDir.resolve("test.jar"), JarType.VALID)
        val output = tempDir.resolve("output.jar")
        val packager = DefaultModulePackager(dryRun = true, feedback = { feedback.add(it) })

        val result = packager.packageModule(listOf(jar), output)

        assertEquals(output.right(), result)
        assertFalse(Files.exists(output))
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "⚠️ Processing valid JAR: ${jar.toString().replace('\\', '/')}",
                "✅ [dry-run] Would package 1 jars into ${output.toString().replace('\\', '/')}"
            ),
            feedback
        )
    }

    @Test
    fun `should skip excluded entries`() {
        val jar = createTestJar(tempDir.resolve("test.jar"), JarType.VALID, mapOf(
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n",
            "module-info.class" to "dummy content",
            "org/eclipse/yasson/internal/components/BeanManagerInstanceCreator.class" to "dummy content"
        ))
        val output = tempDir.resolve("output.jar")
        val packager = DefaultModulePackager(feedback = { feedback.add(it) })

        val result = packager.packageModule(listOf(jar), output)

        assertEquals(output.right(), result)
        JarFile(output.toFile()).use { jarOut ->
            val entries = jarOut.entries().asSequence().map { it.name.replace('\\', '/') }.toSet()
            assertEquals(setOf("com/example/Test.class", "META-INF/services/com.example.Service"), entries)
            val serviceEntry = jarOut.getEntry("META-INF/services/com.example.Service")
            assertNotNull(serviceEntry, "Service entry should exist")
            val serviceContent = jarOut.getInputStream(serviceEntry).bufferedReader().readText().trim()
            assertEquals("com.example.Impl1", serviceContent)
        }
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "⚠️ Processing valid JAR: ${jar.toString().replace('\\', '/')}",
                "⏩ Skipped META-INF/MANIFEST.MF from ${jar.toString().replace('\\', '/')}",
                "⏩ Skipped module-info.class from ${jar.toString().replace('\\', '/')}",
                "⏩ Skipped org/eclipse/yasson/internal/components/BeanManagerInstanceCreator.class from ${jar.toString().replace('\\', '/')}",
                "✅ Merged service com.example.Service with 1 implementations",
                "✅ Packaged 1 jars into ${output.toString().replace('\\', '/')}"
            ),
            feedback
        )
    }

    @Test
    fun `should handle duplicate entries across JARs`() {
        val jar1 = createTestJar(tempDir.resolve("test1.jar"), JarType.VALID)
        val jar2 = createTestJar(tempDir.resolve("test2.jar"), JarType.VALID)
        val output = tempDir.resolve("output.jar")
        val packager = DefaultModulePackager(feedback = { feedback.add(it) })

        val result = packager.packageModule(listOf(jar1, jar2), output)

        assertEquals(output.right(), result)
        JarFile(output.toFile()).use { jar ->
            val entries = jar.entries().asSequence().map { it.name.replace('\\', '/') }.toSet()
            assertEquals(setOf("com/example/Test.class", "META-INF/services/com.example.Service"), entries)
            val serviceEntry = jar.getEntry("META-INF/services/com.example.Service")
            assertNotNull(serviceEntry, "Service entry should exist")
            val serviceContent = jar.getInputStream(serviceEntry).bufferedReader().readText().trim()
            assertEquals("com.example.Impl1", serviceContent)
        }
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "⚠️ Processing valid JAR: ${jar1.toString().replace('\\', '/')}",
                "⚠️ Processing valid JAR: ${jar2.toString().replace('\\', '/')}",
                "⚠️ Skipped duplicate entry com/example/Test.class from ${jar2.toString().replace('\\', '/')}",
                "✅ Merged service com.example.Service with 1 implementations",
                "✅ Packaged 2 jars into ${output.toString().replace('\\', '/')}"
            ),
            feedback
        )
    }

    @Test
    @DisabledIfSystemProperty(named = "os.name", matches = ".*[Ww]indows.*", disabledReason = "PosixFilePermissions not supported on Windows")
    fun `should fail on IO error during packaging`() {
        val jar = createTestJar(tempDir.resolve("test.jar"), JarType.VALID)
        val output = tempDir.resolve("output.jar")
        Files.createDirectories(output.parent)
        Files.setPosixFilePermissions(output.parent, emptySet())
        val packager = DefaultModulePackager(feedback = { feedback.add(it) })

        val result = packager.packageModule(listOf(jar), output)

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is ZpmError.PackagingFailed)
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "❌ Failed to create output directory ${output.parent.toString().replace('\\', '/')}: Permission denied",
                "💥 Packaging failed: Permission denied"
            ),
            feedback
        )
        Files.setPosixFilePermissions(output.parent, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE))
    }

    @Test
    fun `should fail on empty input list`() {
        val output = tempDir.resolve("output.jar")
        val packager = DefaultModulePackager(feedback = { feedback.add(it) })

        val result = packager.packageModule(emptyList(), output)

        assertTrue(result.isLeft())
        assertTrue(result.leftOrNull() is ZpmError.PackagingFailed)
        assertEquals(
            listOf(
                "➡️ Starting packaging to ${output.toString().replace('\\', '/')}",
                "❌ No input JARs provided"
            ),
            feedback
        )
    }
}