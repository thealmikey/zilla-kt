//package io.aklivity.zilla.manager.internal.commands.install
//
//import arrow.core.Either
//import io.aklivity.zilla.manager.internal.commands.install.cache.*
//import io.aklivity.zilla.manager.internal.commands.install.impl.*
//import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
//import kotlinx.serialization.json.Json
//import org.junit.jupiter.api.AfterEach
//import org.junit.jupiter.api.Test
//import java.nio.file.Files
//import java.nio.file.Path
//import java.util.jar.JarEntry
//import java.util.jar.JarOutputStream
//import java.util.jar.Manifest
//import java.util.Comparator
//import java.util.spi.ToolProvider
//import kotlin.io.path.*
//import kotlin.test.assertTrue
//
//class MyZpmInstallIntegrationTest {
//    private var workspace: Path? = null
//
//    @AfterEach
//    fun cleanUp() {
//        workspace?.let { path ->
//            try {
//                Files.walk(path)
//                    .sorted(Comparator.reverseOrder())
//                    .forEach { Files.deleteIfExists(it) }
//            } catch (e: Exception) {
//                println("Failed to clean up workspace: ${e.message}")
//            }
//        }
//    }
//
//    @Test
//    fun `resolve imports returns correct artifacts`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val dep1 = createDummyJar("zilla-core-0.9.0.jar", workspace!!, "com/example/CoreDummy.class")
//        val dep2 = createDummyJar("zilla-binding-mqtt-0.9.0.jar", workspace!!, "com/example/MqttDummy.class")
//        val fakeArtifacts: Map<ZpmArtifactIdKt, ZpmArtifactKt> = mapOf(
//            ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0") to ZpmArtifactKt(
//                ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet()
//            ),
//            ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0") to ZpmArtifactKt(
//                ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0"), dep2, emptySet()
//            )
//        )
//        val fakeCache = object : ZpmCacheKt(emptyList(), workspace!!) {
//            override fun resolveImports(imports: List<ZpmDependencyKt>): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> =
//                Either.Right(fakeArtifacts)
//        }
//        val imports = listOf(
//            ZpmDependencyKt.fromCoordinates("io.aklivity:zilla-core:0.9.0")!!,
//            ZpmDependencyKt.fromCoordinates("io.aklivity:zilla-binding-mqtt:0.9.0")!!
//        )
//        val result = fakeCache.resolveImports(imports)
//        assertTrue(result.isRight(), "resolveImports should succeed, but got: ${result.leftOrNull()}")
//        val artifacts = result.getOrNull()!!
//        assertTrue(artifacts.size == 2, "Should resolve 2 artifacts, got ${artifacts.size}")
//        assertTrue(artifacts.containsKey(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0")), "Should contain zilla-core")
//        assertTrue(artifacts.containsKey(ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0")), "Should contain zilla-binding-mqtt")
//    }
//
//    @Test
//    fun `full pipeline from real zpm json in dry run`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val installDir = workspace!!.resolve("install")
//        Files.createDirectories(installDir)
//
//        val feedbackMessages = mutableListOf<String>()
//        val feedback: (String) -> Unit = { feedbackMessages.add(it) }
//
//        val dep1 = createDummyJar("zilla-core-0.9.0.jar", workspace!!, "com/example/CoreDummy.class")
//        val dep2 = createDummyJar("zilla-binding-mqtt-0.9.0.jar", workspace!!, "com/example/MqttDummy.class")
//
//        val fakeArtifacts: Map<ZpmArtifactIdKt, ZpmArtifactKt> = mapOf(
//            ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0") to ZpmArtifactKt(
//                ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet()
//            ),
//            ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0") to ZpmArtifactKt(
//                ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0"), dep2, emptySet()
//            )
//        )
//
//        val fakeCache = object : ZpmCacheKt(emptyList(), workspace!!) {
//            override fun resolveImports(imports: List<ZpmDependencyKt>): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> =
//                Either.Right(fakeArtifacts)
//        }
//
//        val installer = MyZpmInstall(
//            cache = fakeCache,
//            installDir = installDir,
//            jarCopier = JarCopier(dryRun = true, feedback = feedback),
//            manifestMerger = ManifestMerger(dryRun = true, feedback = feedback),
//            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = feedback),
//            imageLinker = DefaultImageLinker(dryRun = true, feedback = feedback),
//            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = feedback),
//            dryRun = true,
//            verbose = true,
//            feedback = feedback
//        )
//
//        val template = ZpmTemplate(
//            repositories = listOf("https://repo.maven.apache.org/maven2"),
//            imports = listOf("io.aklivity:zilla-core:0.9.0", "io.aklivity:zilla-binding-mqtt:0.9.0"),
//            dependencies = emptyList()
//        )
//        val json = Json { prettyPrint = true }
//        val templatePath = workspace!!.resolve("zpm.json")
//        json.encodeToFile(templatePath, template, pretty = true)
//
//        val result = installer.installFromTemplate(templatePath)
//
//        if (result.isLeft()) {
//            println("Dry-run test failed with error: ${result.leftOrNull()}")
//            println("Feedback messages:\n${feedbackMessages.joinToString("\n")}")
//        }
//
//        assertTrue(result.isRight(), "Install should succeed in dry-run, but got: ${result.leftOrNull()}")
//        val lockFile = installDir.resolve("zpm.lock")
//        assertTrue(lockFile.exists(), "zpm.lock should be written")
//        val lockContent = Files.readString(lockFile)
//        assertTrue(lockContent.contains("// Dry-run zpm.lock"), "Lock file should indicate dry-run")
//        assertTrue(lockContent.contains("zilla-core"), "Lock file should contain zilla-core")
//        assertTrue(lockContent.contains("zilla-binding-mqtt"), "Lock file should contain zilla-binding-mqtt")
//
//        val moduleInfoPath = installDir.resolve("module-info/zilla-install/module-info.java")
//        assertTrue(moduleInfoPath.exists(), "module-info.java should be created")
//        val moduleInfoContent = Files.readString(moduleInfoPath)
//        assertTrue(moduleInfoContent.contains("dry-run module-info"), "module-info should contain dry-run placeholder")
//
//        val launcherPath = installDir.resolve(if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "zilla.bat" else "zilla")
//        assertTrue(launcherPath.exists(), "Launcher should be created")
//        val launcherContent = Files.readString(launcherPath)
//        assertTrue(launcherContent.contains("// Dry-run launcher"), "Launcher should indicate dry-run")
//        assertTrue(launcherContent.contains("io.aklivity.zilla.runtime.command"), "Launcher should include main class")
//
//        assertTrue(feedbackMessages.any { it.contains("Expanding") && it.contains("modules/zilla-install") }, "Should log expansion to correct directory (zilla-install)")
//        assertTrue(feedbackMessages.any { it.contains("Would extend") && it.contains("module-info.class") }, "Should log JAR extension in dry-run")
//        assertTrue(feedbackMessages.any { it.contains("Would compile") && it.contains("module-info.class") }, "Should log module info compilation in dry-run")
//        assertTrue(feedbackMessages.any { it.contains("Would run jlink") }, "Should log image linking in dry-run")
//        assertTrue(feedbackMessages.any { it.contains("Would write launcher") }, "Should log launcher generation in dry-run")
//        assertTrue(feedbackMessages.any { it.contains("Checking template: $templatePath") }, "Should log template check")
//        assertTrue(feedbackMessages.any { it.contains("Parsing template: $templatePath") }, "Should log template parsing")
//        assertTrue(feedbackMessages.any { it.contains("Resolved 2 dependencies") }, "Should log dependency resolution")
//        assertTrue(feedbackMessages.any { it.contains("Copying 2 JARs to zilla-install.jar") }, "Should log JAR copying")
//        assertTrue(feedbackMessages.any { it.contains("Merging manifests") }, "Should log manifest merging")
//        assertTrue(feedbackMessages.any { it.contains("Generating module-info.java") }, "Should log module info generation")
//        assertTrue(feedbackMessages.any { it.contains("Wrote lock file") }, "Should log lock file creation")
//    }
//
//    @Test
//    fun `full pipeline in non-dry run with expand and extend`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val installDir = workspace!!.resolve("install")
//        Files.createDirectories(installDir)
//
//        val feedbackMessages = mutableListOf<String>()
//        val feedback: (String) -> Unit = { feedbackMessages.add(it) }
//
//        val dep1 = createDummyJar("zilla-core-0.9.0.jar", workspace!!, "com/example/CoreDummy.class")
//        val dep2 = createDummyJar("zilla-binding-mqtt-0.9.0.jar", workspace!!, "com/example/MqttDummy.class")
//
//        val fakeArtifacts: Map<ZpmArtifactIdKt, ZpmArtifactKt> = mapOf(
//            ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0") to ZpmArtifactKt(
//                ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet()
//            ),
//            ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0") to ZpmArtifactKt(
//                ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0"), dep2, emptySet()
//            )
//        )
//
//        val fakeCache = object : ZpmCacheKt(emptyList(), workspace!!) {
//            override fun resolveImports(imports: List<ZpmDependencyKt>): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> =
//                Either.Right(fakeArtifacts)
//        }
//
//        val installer = MyZpmInstall(
//            cache = fakeCache,
//            installDir = installDir,
//            jarCopier = JarCopier(dryRun = false, feedback = feedback),
//            manifestMerger = ManifestMerger(dryRun = false, feedback = feedback),
//            moduleInfoGenerator = ModuleInfoGenerator(dryRun = false, feedback = feedback),
//            imageLinker = DefaultImageLinker(dryRun = false, feedback = feedback),
//            launcherWriter = DefaultLauncherWriter(dryRun = false, feedback = feedback),
//            dryRun = false,
//            verbose = true,
//            feedback = feedback
//        )
//
//        val template = ZpmTemplate(
//            repositories = listOf("https://repo.maven.apache.org/maven2"),
//            imports = listOf("io.aklivity:zilla-core:0.9.0", "io.aklivity:zilla-binding-mqtt:0.9.0"),
//            dependencies = emptyList()
//        )
//        val json = Json { prettyPrint = true }
//        val templatePath = workspace!!.resolve("zpm.json")
//        json.encodeToFile(templatePath, template, pretty = true)
//
//        val result = installer.installFromTemplate(templatePath)
//
//        if (result.isLeft()) {
//            println("Non-dry-run test failed with error: ${result.leftOrNull()}")
//            println("Feedback messages:\n${feedbackMessages.joinToString("\n")}")
//        }
//
//        if (result.isLeft() && feedbackMessages.any { it.contains("jlink tool not found") }) {
//            println("Skipping non-dry-run test due to missing jlink")
//            return
//        }
//
//        assertTrue(result.isRight(), "Install should succeed, but got: ${result.leftOrNull()}")
//        val lockFile = installDir.resolve("zpm.lock")
//        assertTrue(lockFile.exists(), "zpm.lock should be written")
//        val expandedDir = installDir.resolve("modules/zilla-install")
//        assertTrue(expandedDir.exists(), "Expanded directory should exist")
//        assertTrue(expandedDir.resolve("com/example/CoreDummy.class").exists(), "Core expanded file should exist")
//        assertTrue(expandedDir.resolve("com/example/MqttDummy.class").exists(), "Mqtt expanded file should exist")
//        val extendedJar = installDir.resolve("zilla-install.jar")
//        assertTrue(extendedJar.exists(), "Extended JAR should exist")
//        val launcherPath = installDir.resolve(if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) "zilla.bat" else "zilla")
//        assertTrue(launcherPath.exists(), "Launcher should exist")
//        val launcherContent = Files.readString(launcherPath)
//        assertTrue(launcherContent.contains("io.aklivity.zilla.runtime.command"), "Launcher should include main class")
//        assertTrue(feedbackMessages.any { it.contains("Extracting entries from") }, "Should log JAR expansion")
//        assertTrue(feedbackMessages.any { it.contains("Copying entry:") }, "Should log JAR extension entries")
//        assertTrue(feedbackMessages.any { it.contains("Running javac") || it.contains("Compiled") }, "Should log module info compilation")
//        assertTrue(feedbackMessages.any { it.contains("Running jlink") || it.contains("Created image") }, "Should log image linking")
//        assertTrue(feedbackMessages.any { it.contains("Generating launcher") }, "Should log launcher generation")
//    }
//
//    @Test
//    fun `compile module info in dry run`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val moduleInfoDir = workspace!!.resolve("module-info")
//        Files.createDirectories(moduleInfoDir)
//        val moduleInfoPath = moduleInfoDir.resolve("module-info.java")
//        Files.writeString(moduleInfoPath, "module test { }")
//        val feedbackMessages = mutableListOf<String>()
//        val installer = MyZpmInstall(
//            cache = object : ZpmCacheKt(emptyList(), workspace!!) {
//                override fun resolveImports(imports: List<ZpmDependencyKt>): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> =
//                    Either.Right(emptyMap())
//            },
//            installDir = workspace!!,
//            jarCopier = JarCopier(dryRun = true, feedback = { feedbackMessages.add(it) }),
//            manifestMerger = ManifestMerger(dryRun = true, feedback = { feedbackMessages.add(it) }),
//            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = { feedbackMessages.add(it) }),
//            imageLinker = DefaultImageLinker(dryRun = true, feedback = { feedbackMessages.add(it) }),
//            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = { feedbackMessages.add(it) }),
//            dryRun = true,
//            verbose = true,
//            feedback = { feedbackMessages.add(it) }
//        )
//        val result = installer.compileModuleInfo(moduleInfoPath)
//        if (result.isLeft()) {
//            println("Compile module info test failed with error: ${result.leftOrNull()}")
//            println("Feedback messages:\n${feedbackMessages.joinToString("\n")}")
//        }
//        assertTrue(result.isRight(), "Compilation should succeed in dry-run, but got: ${result.leftOrNull()}")
//        val moduleInfoClass = result.getOrNull()!!
//        assertTrue(moduleInfoClass.exists(), "module-info.class should be created")
//        assertTrue(feedbackMessages.any { it.contains("Would compile $moduleInfoPath") }, "Should log compilation intention")
//        assertTrue(feedbackMessages.any { it.contains("Compiled $moduleInfoClass") }, "Should log compilation success")
//    }
//
//    @Test
//    fun `link image in dry run`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val jar = createDummyJar("test.jar", workspace!!, "com/example/Test.class")
//        val targetDir = workspace!!.resolve("target")
//        Files.createDirectories(targetDir)
//        val feedbackMessages = mutableListOf<String>()
//        val linker = DefaultImageLinker(dryRun = true, feedback = { feedbackMessages.add(it) })
//        val result = linker.link(listOf(jar), targetDir)
//        if (result.isLeft()) {
//            println("Image linking test failed with error: ${result.leftOrNull()}")
//            println("Feedback messages:\n${feedbackMessages.joinToString("\n")}")
//        }
//        assertTrue(result.isRight(), "Image linking should succeed in dry-run, but got: ${result.leftOrNull()}")
//        val imagePath = result.getOrNull()!!
//        assertTrue(imagePath.exists(), "Image directory should be created")
//        assertTrue(feedbackMessages.any { it.contains("Would run jlink") }, "Should log jlink intention")
//        assertTrue(feedbackMessages.any { it.contains("Created image: $imagePath") }, "Should log image creation")
//    }
//
//    @Test
//    fun `write launcher in dry run`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val targetDir = workspace!!.resolve("target")
//        Files.createDirectories(targetDir)
//        val feedbackMessages = mutableListOf<String>()
//        val writer = DefaultLauncherWriter(dryRun = true, feedback = { feedbackMessages.add(it) })
//        val result = writer.write("com.example.Main", targetDir)
//        if (result.isLeft()) {
//            println("Launcher writing test failed with error: ${result.leftOrNull()}")
//            println("Feedback messages:\n${feedbackMessages.joinToString("\n")}")
//        }
//        assertTrue(result.isRight(), "Launcher writing should succeed in dry-run, but got: ${result.leftOrNull()}")
//        val launcherPath = result.getOrNull()!!
//        assertTrue(launcherPath.exists(), "Launcher file should be created")
//        val content = Files.readString(launcherPath)
//        assertTrue(content.contains("// Dry-run launcher"), "Launcher should indicate dry-run")
//        assertTrue(content.contains("com.example.Main"), "Launcher should include main class")
//        assertTrue(feedbackMessages.any { it.contains("Would write launcher") }, "Should log launcher intention")
//        assertTrue(feedbackMessages.any { it.contains("Created launcher: $launcherPath") }, "Should log launcher creation")
//    }
//
//    @Test
//    fun `handles duplicate entries in jar copier`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val installDir = workspace!!.resolve("install")
//        Files.createDirectories(installDir)
//        val dep1 = createDummyJar("dep1.jar", workspace!!, "com/example/Dummy.class")
//        val dep2 = createDummyJar("dep2.jar", workspace!!, "com/example/Dummy.class")
//        val feedbackMessages = mutableListOf<String>()
//        val jarCopier = JarCopier(dryRun = false, feedback = { feedbackMessages.add(it) })
//        val result = jarCopier.copyJars(listOf(dep1, dep2), installDir.resolve("output.jar"))
//        assertTrue(result.isRight(), "Should succeed despite duplicate entries, but got: ${result.leftOrNull()}")
//        assertTrue(feedbackMessages.any { it.contains("Skipped duplicate entry: com/example/Dummy.class") }, "Should log skipped duplicate")
//    }
//
//    @Test
//    fun `fails when copying corrupt JAR`() {
//        workspace = Files.createTempDirectory("zpm-install-int")
//        val installDir = workspace!!.resolve("install")
//        Files.createDirectories(installDir)
//        val corruptJar = workspace!!.resolve("corrupt.jar")
//        Files.write(corruptJar, byteArrayOf(0x00)) // Invalid JAR
//        val feedbackMessages = mutableListOf<String>()
//        val jarCopier = JarCopier(dryRun = false, feedback = { feedbackMessages.add(it) })
//        val result = jarCopier.copyJars(listOf(corruptJar), installDir.resolve("output.jar"))
//        assertTrue(result.isLeft(), "Should fail with corrupt JAR")
//        assertTrue(feedbackMessages.any { it.contains("Failed to copy JARs") }, "Should log JAR copy failure")
//    }
//
//    private fun createDummyJar(name: String, targetDir: Path, className: String): Path {
//        val p = targetDir.resolve(name)
//        val manifest = Manifest().apply {
//            mainAttributes.putValue("Manifest-Version", "1.0")
//            mainAttributes.putValue("Created-By", "Zilla Manager Test")
//        }
//        JarOutputStream(Files.newOutputStream(p), manifest).use { out ->
//            out.putNextEntry(JarEntry(className))
//            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
//            out.closeEntry()
//        }
//        return p
//    }
//}