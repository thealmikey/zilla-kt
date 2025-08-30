//package io.aklivity.zilla.manager.internal.commands.install.impl
//
//import arrow.core.Either
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactIdKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactKt
//import org.junit.jupiter.api.AfterEach
//import org.junit.jupiter.api.Test
//import org.junit.jupiter.api.io.TempDir
//import java.io.ByteArrayOutputStream
//import java.io.IOException
//import java.io.PrintStream
//import java.nio.file.Files
//import java.nio.file.Path
//import java.util.Optional
//import java.util.jar.JarEntry
//import java.util.jar.JarFile
//import java.util.jar.JarOutputStream
//import kotlin.io.path.createDirectories
//import kotlin.test.assertEquals
//import kotlin.test.assertFalse
//import kotlin.test.assertNotNull
//import kotlin.test.assertTrue
//import org.junit.jupiter.api.BeforeEach
//
//import java.nio.file.StandardCopyOption
//
//class DefaultDelegatingModuleGeneratorTest {
//
//    @BeforeEach
//    fun setUp(@TempDir tempDir: Path) {
//        // Clean up directories before each test
//        val modulesDir = tempDir.resolve("modules")
//        val generatedDir = tempDir.resolve("generated")
//        if (Files.exists(modulesDir)) {
//            Files.walk(modulesDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
//        }
//        if (Files.exists(generatedDir)) {
//            Files.walk(generatedDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
//        }
//    }
//
//    @AfterEach
//    fun tearDown(@TempDir tempDir: Path) {
//        // Clean up directories after each test
//        val modulesDir = tempDir.resolve("modules")
//        val generatedDir = tempDir.resolve("generated")
//        if (Files.exists(modulesDir)) {
//            Files.walk(modulesDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
//        }
//        if (Files.exists(generatedDir)) {
//            Files.walk(generatedDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
//        }
//    }
//
//    private fun createSimpleJar(path: Path): Path {
//        JarOutputStream(Files.newOutputStream(path)).use { jos ->
//            jos.putNextEntry(JarEntry("test.class"))
//            jos.write("test".toByteArray())
//            jos.closeEntry()
//        }
//        return path
//    }
//
//    @Test
//    fun `should handle dry run`(@TempDir tempDir: Path) {
//        val generator = DefaultDelegatingModuleGenerator(
//            modulesDir = tempDir.resolve("modules"),
//            generatedDir = tempDir.resolve("generated"),
//            moduleInfoGenerator = object : ModuleInfoGenerator() {
//                override fun generateDelegate(module: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> =
//                    Either.Right(outputDir.resolve("module-info.java"))
//            },
//            jarExtender = { _, _, _, _ -> Either.Right(Unit) },
//            dryRun = true
//        )
//        val artifact = ZpmArtifactKt(
//            id = ZpmArtifactIdKt("group", "test.delegate", "1.0"),
//            path = tempDir.resolve("test.jar"),
//            dependencies = emptySet()
//        )
//        val module = ZpmModuleKt(
//            name = "test.delegate",
//            id = artifact.id,
//            paths = mutableSetOf(artifact.path),
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//
//        assertEquals("test.delegate", module.name, "Module name should be test.delegate")
//        assertEquals(artifact.id, module.id, "Module ID should match artifact ID")
//        assertEquals(setOf(artifact.path), module.paths, "Module paths should match artifact path")
//        assertEquals(emptySet(), module.depends, "Module dependencies should be empty")
//        assertFalse(module.automatic, "Module should not be automatic")
//        assertTrue(module.delegating, "Module should be delegating")
//
//        // Verify environment is clean
//        val stubJar = tempDir.resolve("modules/test.delegate.jar")
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist before generation")
//        val generatedModuleDir = tempDir.resolve("generated/modules/test.delegate")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist before generation")
//
//        val result = generator.generateDelegating(listOf(module))
//
//        assertTrue(result.isRight(), "Dry run should succeed")
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist in dry run")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist in dry run")
//    }
//
//    @Test
//    fun `should generate stub module with correct module-info`(@TempDir tempDir: Path) {
//        val sourceJar = createSimpleJar(tempDir.resolve("source.jar"))
//        val generator = DefaultDelegatingModuleGenerator(
//            modulesDir = tempDir.resolve("modules").createDirectories(),
//            generatedDir = tempDir.resolve("generated").createDirectories(),
//            moduleInfoGenerator = object : ModuleInfoGenerator() {
//                override fun generateDelegate(module: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
//                    val moduleInfoPath = outputDir.resolve("module-info.java")
//                    Files.writeString(
//                        moduleInfoPath,
//                        "open module ${module.name} {\n    requires transitive io.aklivity.zilla.manager.delegate;\n}"
//                    )
//                    return Either.Right(moduleInfoPath)
//                }
//            },
//            jarExtender = { source, target, entry, entryPath ->
//                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
//                JarOutputStream(Files.newOutputStream(target, java.nio.file.StandardOpenOption.APPEND)).use { jos ->
//                    jos.putNextEntry(entry)
//                    Files.newInputStream(entryPath).copyTo(jos)
//                    jos.closeEntry()
//                }
//                Either.Right(Unit)
//            }
//        )
//        val artifact = ZpmArtifactKt(
//            id = ZpmArtifactIdKt("group", "test.delegate", "1.0"),
//            path = sourceJar,
//            dependencies = emptySet()
//        )
//        val module = ZpmModuleKt(
//            name = "test.delegate",
//            id = artifact.id,
//            paths = mutableSetOf(artifact.path),
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//
//        // Verify environment is clean
//        val stubJar = tempDir.resolve("modules/test.delegate.jar")
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist before generation")
//        val generatedModuleDir = tempDir.resolve("generated/modules/test.delegate")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist before generation")
//
//        val result = generator.generateDelegating(listOf(module))
//
//        assertTrue(result.isRight(), "Stub generation should succeed")
//
//        // Verify stub JAR contents
//        assertTrue(Files.exists(stubJar), "Stub JAR should exist after generation")
//        JarFile(stubJar.toFile()).use { jar ->
//            assertNotNull(jar.getEntry("module-info.class"), "Stub JAR should contain module-info.class")
//            assertNotNull(jar.getEntry("test.class"), "Stub JAR should contain original test.class")
//            assertEquals(2, jar.entries().toList().size, "Stub JAR should contain only module-info.class and test.class")
//        }
//
//        // Verify generated files
//        assertTrue(Files.exists(generatedModuleDir), "Generated module dir should exist after generation")
//        val moduleInfoJava = generatedModuleDir.resolve("module-info.java")
//        assertTrue(Files.exists(moduleInfoJava), "module-info.java should exist")
//        val content = Files.readString(moduleInfoJava).trim()
//        val expectedContent = "open module test.delegate {\n    requires transitive io.aklivity.zilla.manager.delegate;\n}"
//        assertEquals(expectedContent, content, "module-info.java content should match expected")
//
//        val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
//        assertTrue(Files.exists(moduleInfoClass), "module-info.class should exist")
//    }
//
//    @Test
//    fun `should fail when module-info class not generated`(@TempDir tempDir: Path) {
//        val generator = DefaultDelegatingModuleGenerator(
//            modulesDir = tempDir.resolve("modules").createDirectories(),
//            generatedDir = tempDir.resolve("generated").createDirectories(),
//            moduleInfoGenerator = object : ModuleInfoGenerator() {
//                override fun generateDelegate(module: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
//                    val moduleInfoPath = outputDir.resolve("module-info.java")
//                    Files.writeString(moduleInfoPath, "open module ${module.name} {\n    requires transitive io.aklivity.zilla.manager.delegate;\n}")
//                    return Either.Right(moduleInfoPath)
//                }
//            },
//            jarExtender = { _, _, _, _ -> Either.Right(Unit) }
//        )
//        val module = ZpmModuleKt(
//            name = "missing.class",
//            id = ZpmArtifactIdKt("group", "missing.class", "1.0"),
//            paths = mutableSetOf(tempDir.resolve("dummy.jar")),
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//
//        // Verify environment is clean
//        val stubJar = tempDir.resolve("modules/missing.class.jar")
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist before generation")
//        val generatedModuleDir = tempDir.resolve("generated/modules/missing.class")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist before generation")
//
//        // Simulate javac not producing module-info.class by deleting it after generation
//        val result = generator.generateDelegating(listOf(module)).also {
//            val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
//            Files.deleteIfExists(moduleInfoClass)
//        }
//
//        assertTrue(result.isLeft(), "Should fail when module-info.class is not generated")
//
//        // Verify no stub JAR created
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist after failure")
//    }
//
//    @Test
//    fun `should fail when javac not found`(@TempDir tempDir: Path) {
//        val generator = DefaultDelegatingModuleGenerator(
//            modulesDir = tempDir.resolve("modules").createDirectories(),
//            generatedDir = tempDir.resolve("generated").createDirectories(),
//            moduleInfoGenerator = object : ModuleInfoGenerator() {
//                override fun generateDelegate(module: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
//                    val moduleInfoPath = outputDir.resolve("module-info.java")
//                    Files.writeString(moduleInfoPath, "open module ${module.name} {\n    requires transitive io.aklivity.zilla.manager.delegate;\n}")
//                    return Either.Right(moduleInfoPath)
//                }
//            },
//            jarExtender = { _, _, _, _ -> Either.Right(Unit) }
//        )
//        val module = ZpmModuleKt(
//            name = "missing.javac",
//            id = ZpmArtifactIdKt("group", "missing.javac", "1.0"),
//            paths = mutableSetOf(tempDir.resolve("dummy.jar")),
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//
//        // Verify environment is clean
//        val stubJar = tempDir.resolve("modules/missing.javac.jar")
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist before generation")
//        val generatedModuleDir = tempDir.resolve("generated/modules/missing.javac")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist before generation")
//
//        // Simulate javac not found by overriding ToolProvider.findFirst
//        System.setProperty("java.home", tempDir.toString()) // Set to invalid path
//        val result = generator.generateDelegating(listOf(module))
//
//        assertTrue(result.isLeft(), "Should fail when javac is not found")
//
//        // Verify no stub JAR or generated files created
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist after failure")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist after failure")
//    }
//
//    @Test
//    fun `should fail when javac returns non-zero exit code`(@TempDir tempDir: Path) {
//        val generator = DefaultDelegatingModuleGenerator(
//            modulesDir = tempDir.resolve("modules").createDirectories(),
//            generatedDir = tempDir.resolve("generated").createDirectories(),
//            moduleInfoGenerator = object : ModuleInfoGenerator() {
//                override fun generateDelegate(module: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
//                    val moduleInfoPath = outputDir.resolve("module-info.java")
//                    Files.writeString(moduleInfoPath, "invalid syntax") // Cause javac failure
//                    return Either.Right(moduleInfoPath)
//                }
//            },
//            jarExtender = { _, _, _, _ -> Either.Right(Unit) }
//        )
//        val module = ZpmModuleKt(
//            name = "failing.javac",
//            id = ZpmArtifactIdKt("group", "failing.javac", "1.0"),
//            paths = mutableSetOf(tempDir.resolve("dummy.jar")),
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//
//        // Verify environment is clean
//        val stubJar = tempDir.resolve("modules/failing.javac.jar")
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist before generation")
//        val generatedModuleDir = tempDir.resolve("generated/modules/failing.javac")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist before generation")
//
//        val result = generator.generateDelegating(listOf(module))
//
//        assertTrue(result.isLeft(), "Should fail when javac returns non-zero exit code")
//
//        // Verify no stub JAR created
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist after failure")
//    }
//
//    @Test
//    fun `should fail when module paths is empty`(@TempDir tempDir: Path) {
//        val generator = DefaultDelegatingModuleGenerator(
//            modulesDir = tempDir.resolve("modules").createDirectories(),
//            generatedDir = tempDir.resolve("generated").createDirectories(),
//            moduleInfoGenerator = object : ModuleInfoGenerator() {
//                override fun generateDelegate(module: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
//                    val moduleInfoPath = outputDir.resolve("module-info.java")
//                    Files.writeString(moduleInfoPath, "open module ${module.name} {\n    requires transitive io.aklivity.zilla.manager.delegate;\n}")
//                    return Either.Right(moduleInfoPath)
//                }
//            },
//            jarExtender = { _, _, _, _ -> Either.Right(Unit) }
//        )
//        val module = ZpmModuleKt(
//            name = "empty.paths",
//            id = ZpmArtifactIdKt("group", "empty.paths", "1.0"),
//            paths = mutableSetOf(), // Empty paths
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//
//        // Verify environment is clean
//        val stubJar = tempDir.resolve("modules/empty.paths.jar")
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist before generation")
//        val generatedModuleDir = tempDir.resolve("generated/modules/empty.paths")
//        assertFalse(Files.exists(generatedModuleDir), "Generated module dir should not exist before generation")
//
//        val result = generator.generateDelegating(listOf(module))
//
//        assertTrue(result.isLeft(), "Should fail when module paths is empty")
//        assertTrue(result.leftOrNull()?.message?.contains("No artifact path") ?: false, "Error should indicate missing artifact path")
//
//        // Verify no stub JAR created
//        assertFalse(Files.exists(stubJar), "Stub JAR should not exist after failure")
//    }
//
//    @Test
//    fun `should handle multiple delegating modules`(@TempDir tempDir: Path) {
//        val sourceJar1 = createSimpleJar(tempDir.resolve("source1.jar"))
//        val sourceJar2 = createSimpleJar(tempDir.resolve("source2.jar"))
//        val generator = DefaultDelegatingModuleGenerator(
//            modulesDir = tempDir.resolve("modules").createDirectories(),
//            generatedDir = tempDir.resolve("generated").createDirectories(),
//            moduleInfoGenerator = object : ModuleInfoGenerator() {
//                override fun generateDelegate(module: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
//                    val moduleInfoPath = outputDir.resolve("module-info.java")
//                    Files.writeString(
//                        moduleInfoPath,
//                        "open module ${module.name} {\n    requires transitive io.aklivity.zilla.manager.delegate;\n}"
//                    )
//                    return Either.Right(moduleInfoPath)
//                }
//            },
//            jarExtender = { source, target, entry, entryPath ->
//                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
//                JarOutputStream(Files.newOutputStream(target, java.nio.file.StandardOpenOption.APPEND)).use { jos ->
//                    jos.putNextEntry(entry)
//                    Files.newInputStream(entryPath).copyTo(jos)
//                    jos.closeEntry()
//                }
//                Either.Right(Unit)
//            }
//        )
//        val module1 = ZpmModuleKt(
//            name = "test.delegate1",
//            id = ZpmArtifactIdKt("group", "test.delegate1", "1.0"),
//            paths = mutableSetOf(sourceJar1),
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//        val module2 = ZpmModuleKt(
//            name = "test.delegate2",
//            id = ZpmArtifactIdKt("group", "test.delegate2", "1.0"),
//            paths = mutableSetOf(sourceJar2),
//            depends = mutableSetOf(),
//            automatic = true,
//            delegating = true
//        )
//
//        // Verify environment is clean
//        val stubJar1 = tempDir.resolve("modules/test.delegate1.jar")
//        val stubJar2 = tempDir.resolve("modules/test.delegate2.jar")
//        assertFalse(Files.exists(stubJar1), "Stub JAR 1 should not exist before generation")
//        assertFalse(Files.exists(stubJar2), "Stub JAR 2 should not exist before generation")
//        val generatedModuleDir1 = tempDir.resolve("generated/modules/test.delegate1")
//        val generatedModuleDir2 = tempDir.resolve("generated/modules/test.delegate2")
//        assertFalse(Files.exists(generatedModuleDir1), "Generated module dir 1 should not exist before generation")
//        assertFalse(Files.exists(generatedModuleDir2), "Generated module dir 2 should not exist before generation")
//
//        val result = generator.generateDelegating(listOf(module1, module2))
//
//        assertTrue(result.isRight(), "Stub generation should succeed for multiple modules")
//
//        // Verify stub JARs
//        assertTrue(Files.exists(stubJar1), "Stub JAR 1 should exist after generation")
//        assertTrue(Files.exists(stubJar2), "Stub JAR 2 should exist after generation")
//        JarFile(stubJar1.toFile()).use { jar ->
//            assertNotNull(jar.getEntry("module-info.class"), "Stub JAR 1 should contain module-info.class")
//            assertNotNull(jar.getEntry("test.class"), "Stub JAR 1 should contain original test.class")
//        }
//        JarFile(stubJar2.toFile()).use { jar ->
//            assertNotNull(jar.getEntry("module-info.class"), "Stub JAR 2 should contain module-info.class")
//            assertNotNull(jar.getEntry("test.class"), "Stub JAR 2 should contain original test.class")
//        }
//    }
//}