//package io.aklivity.zilla.manager.internal.commands.install
//
//import arrow.core.Either
//import arrow.core.NonEmptyList
//import arrow.core.right
//import arrow.core.left
//import arrow.core.toNonEmptyListOrNull
//import io.aklivity.zilla.manager.internal.commands.install.cache.*
//import io.aklivity.zilla.manager.internal.commands.install.impl.*
//import io.github.oshai.kotlinlogging.KotlinLogging
//import kotlinx.serialization.Serializable
//import org.junit.jupiter.api.Test
//import java.nio.file.Files
//import java.nio.file.Path
//import java.util.jar.JarEntry
//import java.util.jar.JarFile
//import java.util.jar.JarOutputStream
//import java.util.jar.Manifest
//import java.util.spi.ToolProvider
//import kotlin.io.path.createDirectories
//import kotlin.io.path.outputStream
//import kotlin.io.path.writeText
//import kotlin.test.assertEquals
//import kotlin.test.assertFalse
//import kotlin.test.assertTrue
//import kotlin.test.assertNotNull
//
//@Serializable
//data class MyState(val id: Int)
//
//class MyZpmInstallIntegrationTest {
//    private val logger = KotlinLogging.logger {}
//    private val workspace = Files.createTempDirectory("zilla-test")
//    private val feedbackMessages = mutableListOf<String>()
//    private val installer = object : MyZpmInstall(
//        cache = ZpmCacheKt(emptyList(), workspace),
//        installDir = workspace.resolve("install").createDirectories(),
//        jarCopier = JarCopier(dryRun = false, feedback = {logger.info(it); feedbackMessages.add(it) }),
//        manifestMerger = ManifestMerger(dryRun = false, feedback = { logger.info(it);feedbackMessages.add(it) }),
//        moduleInfoGenerator = ModuleInfoGenerator(dryRun = false, feedback = {logger.info(it); feedbackMessages.add(it) }),
//        imageLinker = DefaultImageLinker(dryRun = false,  feedback = { logger.info(it);feedbackMessages.add(it) }),
//        launcherWriter = DefaultLauncherWriter(dryRun = false, feedback = {logger.info(it); feedbackMessages.add(it) }),
//        ignoreMissingDependencies = true,
//        verbose = true,
//        feedback = {logger.info(it); feedbackMessages.add(it) } // Ensure feedback is passed to constructor
//    ) {
//        override fun atLeastVersion(tool: ToolProvider, major: Int): Boolean = true
//        override fun expandJar(sourcePath: Path, targetDir: Path): Either<ZpmResolutionErrorKt, Unit> {
//            feedbackMessages.add("📂 Mock expanding $sourcePath to $targetDir")
//            targetDir.resolve("com/tinder/statemachine/State.class").parent.createDirectories()
//            targetDir.resolve("com/tinder/statemachine/State.class").writeText("dummy statemachine content")
//            targetDir.resolve("META-INF/services/com.example.Service").parent.createDirectories()
//            targetDir.resolve("META-INF/services/com.example.Service").writeText("com.example.ServiceImpl")
//            return Either.Right(Unit)
//        }
//        override fun extendJar(sourcePath: Path, targetPath: Path, newEntry: JarEntry, newEntryPath: Path): Either<ZpmResolutionErrorKt, Unit> {
//            feedbackMessages.add("📦 Mock extending $sourcePath with ${newEntry.name}")
//            JarOutputStream(targetPath.outputStream()).use { jar ->
//                jar.putNextEntry(JarEntry("com/tinder/statemachine/State.class"))
//                jar.write("dummy statemachine content".toByteArray())
//                jar.closeEntry()
//                jar.putNextEntry(JarEntry("META-INF/services/com.example.Service"))
//                jar.write("com.example.ServiceImpl".toByteArray())
//                jar.closeEntry()
//                jar.putNextEntry(newEntry)
//                jar.write("mock module-info".toByteArray())
//                jar.closeEntry()
//            }
//
//            feedbackMessages.add(" ✅ Added new entry: ${newEntry.name}")
//            return Either.Right(Unit)
//        }
//        override fun discoverModules(artifacts: List<ZpmArtifactKt>, feedback: ((String) -> Unit)?): Collection<ZpmModuleKt> {
//            val modules = mutableListOf<ZpmModuleKt>()
//            val systemModulePrefixes = setOf("java.", "jdk.")
//            artifacts.forEach { artifact ->
//                val coordinate = artifact.id.toString()
//                if (!isValidArtifactCoordinate(coordinate)) {
//                    feedback?.invoke("⚠️ Skipping invalid artifact coordinate: $coordinate")
//                    return@forEach
//                }
//                feedback?.invoke("📄 Processing artifact: $coordinate")
//                val isStateMachine = artifact.id.artifactId == "statemachine" && artifact.id.groupId == "com.tinder"
//                if (isStateMachine) {
//                    feedback?.invoke("✅ Found unnamed module for $coordinate")
//                    modules.add(ZpmModuleKt(
//                        name = null,
//                        id = artifact.id,
//                        paths = mutableSetOf(artifact.path),
//                        depends = emptySet()
//                    ))
//                } else {
//                    val moduleName = when (artifact.id.artifactId) {
//                        "kotlinx-serialization-json" -> "kotlinx.serialization.json"
//                        "arrow-core" -> "arrow.core"
//                        "mymodule" -> "com.example.mymodule"
//                        else -> artifact.id.artifactId
//                    }
//                    feedback?.invoke("✅ Found module: $moduleName")
//                    modules.add(ZpmModuleKt(
//                        name = moduleName,
//                        id = artifact.id,
//                        paths = mutableSetOf(artifact.path),
//                        depends = artifact.dependencies,
//                        automatic = true,
//                        delegating = false
//                    ))
//                }
//            }
//            feedback?.invoke("✅ Discovered ${modules.size} modules")
//            return modules
//        }
//        override fun migrateUnnamed(
//            modules: MutableCollection<ZpmModuleKt>,
//            delegate: ZpmModuleKt
//        ): Either<NonEmptyList<String>, Collection<Path>> {
//            feedback?.invoke("🚚 Starting migration of unnamed modules to delegate")
//            println("MIKE: Invoking migrateUnnamed with ${modules.size} modules")
//            val errors = mutableListOf<String>()
//            val migratedPaths = mutableSetOf<Path>()
//            val iterator = modules.iterator()
//            var migratedCount = 0
//            while (iterator.hasNext()) {
//                val module = iterator.next()
//                if (module.name == null) {
//                    module.paths.forEach { path ->
//                        if (delegate.paths.add(path)) {
//                            migratedPaths.add(path)
//                            feedback?.invoke("✅ Migrated path $path to delegate")
//                            println("MIKE: Logged migration feedback for path: $path")
//                        } else {
//                            errors.add("Duplicate path in delegate: $path")
//                            feedback?.invoke("⚠️ Duplicate path in delegate: $path")
//                            println("MIKE: Logged duplicate path warning: $path")
//                        }
//                    }
//                    iterator.remove()
//                    migratedCount++
//                }
//            }
//            if (migratedCount == 0) {
//                feedback?.invoke("⚠️ No unnamed modules found to migrate")
//                println("MIKE: No unnamed modules found to migrate")
//            } else {
//                feedback?.invoke("✅ Migrated $migratedCount unnamed modules to delegate")
//                println("MIKE: Completed migration of $migratedCount unnamed modules")
//            }
//            return if (errors.isNotEmpty()) {
//                errors.toNonEmptyListOrNull()?.let { it.left() } ?: migratedPaths.right()
//            } else {
//                migratedPaths.right()
//            }
//        }
//    }
//
//    @Test
//    fun `should generate delegate JAR with module-info`() {
//        val serializationJar = createDummyJarWithManifest(
//            "kotlinx-serialization-json-1.8.0.jar",
//            workspace,
//            Manifest().apply { mainAttributes.putValue("Automatic-Module-Name", "kotlinx.serialization.json") },
//            "kotlinx/serialization/Serializer.class"
//        )
//        val arrowJar = createDummyJarWithManifest(
//            "arrow-core-2.1.0.jar",
//            workspace,
//            Manifest().apply { mainAttributes.putValue("Automatic-Module-Name", "arrow.core") },
//            "arrow/core/Either.class"
//        )
//        val stateMachineJar = createDummyJar(
//            "statemachine-0.2.0.jar",
//            workspace,
//            "com/tinder/statemachine/State.class",
//            "META-INF/services/com.example.Service"
//        )
//        val myModuleJar = createDummyJarWithManifest(
//            "mymodule-1.0.0.jar",
//            workspace,
//            Manifest().apply { mainAttributes.putValue("Automatic-Module-Name", "com.example.mymodule") },
//            "com/example/mymodule/MyState.class"
//        )
//        val artifacts = listOf(
//            ZpmArtifactKt(ZpmArtifactIdKt("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.8.0"), serializationJar, emptySet()),
//            ZpmArtifactKt(ZpmArtifactIdKt("io.arrow-kt", "arrow-core", "2.1.0"), arrowJar, emptySet()),
//            ZpmArtifactKt(ZpmArtifactIdKt("com.tinder", "statemachine", "0.2.0"), stateMachineJar, emptySet()),
//            ZpmArtifactKt(ZpmArtifactIdKt("com.example", "mymodule", "1.0.0"), myModuleJar, setOf(
//                ZpmArtifactIdKt("org.jetbrains.kotlinx", "kotlinx-serialization-json", "1.8.0"),
//                ZpmArtifactIdKt("io.arrow-kt", "arrow-core", "2.1.0"),
//                ZpmArtifactIdKt("com.tinder", "statemachine", "0.2.0")
//            ))
//        )
//
//        val modules = installer.discoverModules(artifacts, feedbackMessages::add).toMutableList()
//        println("MIKE: discoverModule: $modules")
//        assertFalse(modules.any { it.name?.startsWith("java.") == true }, "java.base should be filtered out")
//        assertTrue(modules.any { it.id.toString() == "com.tinder:statemachine:0.2.0" && it.name == null }, "statemachine should be unnamed")
//
//        val delegate = ZpmModuleKt(name = null, id = null, paths = mutableSetOf())
//        val migrateResult = installer.migrateUnnamed(modules, delegate)
//        println("MIKE: migrateResult: $migrateResult")
//        assertTrue(migrateResult is Either.Right, "Migrate should succeed")
//        assertEquals(mutableSetOf(stateMachineJar), delegate.paths, "Delegate should contain statemachine")
//        println("MIKE: delegate.paths before generateDelegate: ${delegate.paths}")
//
//        val targetJar = workspace.resolve("install/modules/zilla.delegate.jar")
//        val generateDelegateResult = installer.generateDelegate(delegate, targetJar, feedbackMessages::add)
//        println("MIKE: generateDelegateResult: $generateDelegateResult")
//        assertTrue(generateDelegateResult is Either.Right, "Generate delegate should succeed")
//
//        assertTrue(Files.exists(targetJar), "Delegate JAR should exist")
//        JarFile(targetJar.toFile()).use { jar ->
//            assertNotNull(jar.getEntry("com/tinder/statemachine/State.class"), "Delegate JAR should contain statemachine classes")
//            assertNotNull(jar.getEntry("META-INF/services/com.example.Service"), "Delegate JAR should contain merged services")
//            assertTrue(
//                jar.getEntry("module-info.class") != null || jar.getEntry("META-INF/versions/9/module-info.class") != null,
//                "Delegate JAR should contain module-info.class"
//            )
//        }
//
//        println("Content in feedback messages: ${feedbackMessages}")
//        assertTrue(feedbackMessages.any { it.contains("📝 Generating delegate module: zilla.delegate") }, "Should log delegate generation")
//        assertTrue(feedbackMessages.any { it.contains("✅ Adding entry: com/tinder/statemachine/State.class") }, "Should log statemachine class addition")
//        assertTrue(feedbackMessages.any { it.contains("✅ Found service: com.example.Service") }, "Should log service discovery")
//        assertTrue(feedbackMessages.any { it.contains("✅ Writing merged service: com.example.Service") }, "Should log service merging")
//        assertTrue(
//            feedbackMessages.any { it.contains("✅ Added new entry: module-info.class") },
//            "Should log module-info addition"
//        )
//        assertTrue(feedbackMessages.any { it.contains("✅ Found unnamed module for com.tinder:statemachine:0.2.0") }, "Should log statemachine as unnamed")
//        assertTrue(
//            feedbackMessages.any { it.contains("Migrated path") && it.contains("statemachine-0.2.0.jar") },
//            "Should log statemachine migration"
//        )
//    }
//
//    private fun createDummyJar(name: String, workspace: Path, vararg classes: String): Path {
//        val jarPath = workspace.resolve(name)
//        JarOutputStream(jarPath.outputStream()).use { jar ->
//            classes.forEach { className ->
//                val entry = JarEntry(className)
//                jar.putNextEntry(entry)
//                if (className == "META-INF/services/com.example.Service") {
//                    jar.write("com.example.ServiceImpl".toByteArray())
//                } else {
//                    jar.write("dummy content".toByteArray())
//                }
//                jar.closeEntry()
//            }
//        }
//        return jarPath
//    }
//
//    private fun createDummyJarWithManifest(name: String, workspace: Path, manifest: Manifest, vararg classes: String): Path {
//        val jarPath = workspace.resolve(name)
//        JarOutputStream(jarPath.outputStream(), manifest).use { jar ->
//            classes.forEach { className ->
//                val entry = JarEntry(className)
//                jar.putNextEntry(entry)
//                jar.write("dummy content".toByteArray())
//                jar.closeEntry()
//            }
//        }
//        return jarPath
//    }
//}