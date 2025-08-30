//package io.aklivity.zilla.manager.internal.commands.install.impl
//
//import arrow.core.Either
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
//import org.junit.jupiter.api.AfterEach
//import org.junit.jupiter.api.BeforeEach
//import org.junit.jupiter.api.Test
//import java.nio.file.*
//import java.util.jar.*
//import kotlin.io.path.createDirectories
//import kotlin.io.path.exists
//import kotlin.io.path.readText
//import kotlin.test.*
//
//class ModuleInfoGeneratorTest {
//
//    private lateinit var tempDir: Path
//    private val feedbackMessages = mutableListOf<String>()
//    private val feedback: (String) -> Unit = { feedbackMessages.add(it) }
//
//    @BeforeEach
//    fun setUp() {
//        tempDir = Files.createTempDirectory("module-info-test")
//    }
//
//    @AfterEach
//    fun tearDown() {
//        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
//    }
//
//    private fun createDummyJar(name: String, entries: Map<String, String> = mapOf("com/example/Test.class" to "dummy")): Path {
//        val path = tempDir.resolve(name)
//        Files.createDirectories(path.parent)
//        JarOutputStream(Files.newOutputStream(path)).use { out ->
//            entries.forEach { (name, content) ->
//                out.putNextEntry(JarEntry(name).apply { time = 318240000000L }) // Set consistent timestamp
//                out.write(content.toByteArray())
//                out.closeEntry()
//            }
//            // Add a minimal manifest to ensure valid JAR
//            out.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
//            out.write("Manifest-Version: 1.0\n".toByteArray())
//            out.closeEntry()
//        }
//        // Verify JAR contents
//        JarFile(path.toFile()).use { jar ->
//            val entryNames = jar.entries().asSequence().map { it.name }.toList()
//            feedback("Created JAR $path with entries: $entryNames")
//        }
//        return path
//    }
//
//    @Test
//    fun `should generate module-info-java successfully`() {
//        val jarPath = createDummyJar("test.jar")
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val delegate = ZpmModuleKt(name = "zilla.delegate", paths = mutableSetOf(jarPath))
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generate(jarPath, outputDir, delegate)
//
//        assertTrue(result.isRight(), "Expected Right, got $result")
//        val moduleInfoPath = result.getOrNull()
//        assertEquals(outputDir.resolve("module-info/zilla-install/module-info.java"), moduleInfoPath)
//        assertTrue(moduleInfoPath!!.exists(), "Module info file should exist: $moduleInfoPath")
//        val content = moduleInfoPath.readText()
//        feedback("Generated module-info.java content:\n$content")
//        assertTrue(content.contains("module zilla.install"), "Expected 'module zilla.install' in content")
//        assertTrue(content.contains("requires zilla.delegate"), "Expected 'requires zilla.delegate' in content")
//        assertTrue(content.contains("com/example/Test.class"), "Expected 'com/example/Test.class' in content")
//        assertTrue(feedbackMessages.any { it.contains("✅ Generated") }, "Expected feedback '✅ Generated' in messages: $feedbackMessages")
//    }
//
//    @Test
//    fun `should handle dry-run`() {
//        val jarPath = createDummyJar("test.jar")
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val generator = ModuleInfoGenerator(dryRun = true, feedback = feedback)
//
//        val result = generator.generate(jarPath, outputDir, ZpmModuleKt())
//
//        assertTrue(result.isRight(), "Expected Right, got $result")
//        val moduleInfoPath = result.getOrNull()
//        assertTrue(moduleInfoPath!!.exists())
//        assertTrue(moduleInfoPath.readText().contains("Dry-run module-info"))
//        assertTrue(feedbackMessages.any { it.contains("[dry-run] Would generate module-info.java") })
//    }
//
//    @Test
//    fun `should fail on invalid jar`() {
//        val jarPath = tempDir.resolve("nonexistent.jar")
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generate(jarPath, outputDir, ZpmModuleKt())
//
//        assertTrue(result.isLeft(), "Expected Left, got $result")
//        assertTrue(feedbackMessages.any { it.contains("JAR $jarPath is invalid or unreadable") })
//    }
//
//    @Test
//    fun `should fail on corrupt jar`() {
//        val jarPath = tempDir.resolve("corrupt.jar")
//        Files.write(jarPath, "not a jar".toByteArray()) // Create invalid file
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generate(jarPath, outputDir, ZpmModuleKt())
//
//        assertTrue(result.isLeft(), "Expected Left, got $result")
//        assertTrue(feedbackMessages.any { it.contains("Error generating module-info") })
//    }
//
//    @Test
//    fun `generateDelegate should create delegate module-info`() {
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val delegate = ZpmModuleKt(name = "zilla.delegate", paths = mutableSetOf(tempDir.resolve("dummy.jar")))
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generateDelegate(delegate, outputDir)
//
//        assertTrue(result.isRight(), "Expected Right, got $result")
//        val moduleInfoPath = result.getOrNull()
//        assertEquals(outputDir.resolve("module-info/zilla-install/module-info.java"), moduleInfoPath)
//        assertTrue(moduleInfoPath!!.exists())
//        val content = moduleInfoPath.readText()
//        assertTrue(content.contains("module zilla.delegate"))
//        assertTrue(content.contains("requires transitive zilla.delegate"))
//        assertTrue(feedbackMessages.any { it.contains("✅ Generated") })
//    }
//
//    @Test
//    fun `generateDelegate should handle dry-run`() {
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val delegate = ZpmModuleKt(name = "zilla.delegate", paths = mutableSetOf(tempDir.resolve("dummy.jar")))
//        val generator = ModuleInfoGenerator(dryRun = true, feedback = feedback)
//
//        val result = generator.generateDelegate(delegate, outputDir)
//
//        assertTrue(result.isRight(), "Expected Right, got $result")
//        val moduleInfoPath = result.getOrNull()
//        assertTrue(moduleInfoPath!!.exists())
//        assertTrue(moduleInfoPath.readText().contains("Dry-run delegate"))
//        assertTrue(feedbackMessages.any { it.contains("[dry-run] Would generate delegate module-info.java") })
//    }
//
//    @Test
//    fun `generateDelegate should fail on invalid delegate`() {
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val delegate = ZpmModuleKt(name = null, paths = mutableSetOf())
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generateDelegate(delegate, outputDir)
//
//        assertTrue(result.isLeft(), "Expected Left, got $result")
//        assertTrue(feedbackMessages.any { it.contains("Invalid delegate module") })
//    }
//
//    @Test
//    fun `should generate module-info for empty JAR`() {
//        val jarPath = createDummyJar("empty.jar", entries = emptyMap())
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val delegate = ZpmModuleKt(name = "zilla.delegate", paths = mutableSetOf(jarPath))
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generate(jarPath, outputDir, delegate)
//
//        assertTrue(result.isRight(), "Expected Right, got $result")
//        val moduleInfoPath = result.getOrNull()
//        assertEquals(outputDir.resolve("module-info/zilla-install/module-info.java"), moduleInfoPath)
//        assertTrue(moduleInfoPath!!.exists(), "Module info file should exist: $moduleInfoPath")
//        val content = moduleInfoPath.readText()
//        feedback("Generated module-info.java content:\n$content")
//        assertTrue(content.contains("module zilla.install"), "Expected 'module zilla.install' in content")
//        assertTrue(content.contains("requires zilla.delegate"), "Expected 'requires zilla.delegate' in content")
//        assertFalse(content.contains("// com/example/Test.class"), "Expected no class entries in content")
//        assertTrue(feedbackMessages.any { it.contains("✅ Generated") }, "Expected feedback '✅ Generated' in messages: $feedbackMessages")
//    }
//
//    @Test
//    fun `should generate module-info for JAR with non-class entries`() {
//        val jarPath = createDummyJar("non-class.jar", entries = mapOf(
//            "com/example/Test.class" to "dummy",
//            "com/example/config.txt" to "config data",
//            "com/example/" to "" // Directory entry
//        ))
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val delegate = ZpmModuleKt(name = "zilla.delegate", paths = mutableSetOf(jarPath))
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generate(jarPath, outputDir, delegate)
//
//        assertTrue(result.isRight(), "Expected Right, got $result")
//        val moduleInfoPath = result.getOrNull()
//        assertEquals(outputDir.resolve("module-info/zilla-install/module-info.java"), moduleInfoPath)
//        assertTrue(moduleInfoPath!!.exists(), "Module info file should exist: $moduleInfoPath")
//        val content = moduleInfoPath.readText()
//        feedback("Generated module-info.java content:\n$content")
//        assertTrue(content.contains("module zilla.install"), "Expected 'module zilla.install' in content")
//        assertTrue(content.contains("requires zilla.delegate"), "Expected 'requires zilla.delegate' in content")
//        assertTrue(content.contains("com/example/Test.class"), "Expected 'com/example/Test.class' in content")
//        assertFalse(content.contains("config.txt"), "Expected no 'config.txt' in content")
//        assertFalse(content.contains("com/example/"), "Expected no directory entries in content")
//        assertTrue(feedbackMessages.any { it.contains("✅ Generated") }, "Expected feedback '✅ Generated' in messages: $feedbackMessages")
//    }
//
//    @Test
//    fun `generateDelegate should handle multiple paths`() {
//        val outputDir = tempDir.resolve("output").createDirectories()
//        val delegate = ZpmModuleKt(
//            name = "zilla.delegate",
//            paths = mutableSetOf(
//                createDummyJar("jar1.jar"),
//                createDummyJar("jar2.jar"),
//                tempDir.resolve("dummy.jar")
//            )
//        )
//        val generator = ModuleInfoGenerator(feedback = feedback)
//
//        val result = generator.generateDelegate(delegate, outputDir)
//
//        assertTrue(result.isRight(), "Expected Right, got $result")
//        val moduleInfoPath = result.getOrNull()
//        assertEquals(outputDir.resolve("module-info/zilla-install/module-info.java"), moduleInfoPath)
//        assertTrue(moduleInfoPath!!.exists())
//        val content = moduleInfoPath.readText()
//        assertTrue(content.contains("module zilla.delegate"), "Expected 'module zilla.delegate' in content")
//        assertTrue(content.contains("requires transitive zilla.delegate"), "Expected 'requires transitive zilla.delegate' in content")
//        assertTrue(content.contains("jar1.jar"), "Expected 'jar1.jar' in content")
//        assertTrue(content.contains("jar2.jar"), "Expected 'jar2.jar' in content")
//        assertTrue(content.contains("dummy.jar"), "Expected 'dummy.jar' in content")
//        assertTrue(feedbackMessages.any { it.contains("✅ Generated") }, "Expected feedback '✅ Generated' in messages: $feedbackMessages")
//    }
//}