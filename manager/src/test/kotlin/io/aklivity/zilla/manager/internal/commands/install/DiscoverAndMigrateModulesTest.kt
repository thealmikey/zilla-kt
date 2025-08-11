package io.aklivity.zilla.manager.internal.commands.install

import arrow.core.Either
import arrow.core.NonEmptyList
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactIdKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmCacheKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmDependencyKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultImageLinker
import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultLauncherWriter
import io.aklivity.zilla.manager.internal.commands.install.impl.JarCopier
import io.aklivity.zilla.manager.internal.commands.install.impl.ManifestMerger
import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmModuleKt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.io.path.createDirectories
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoverAndMigrateModulesTest {
    private lateinit var workspace: Path
    private lateinit var installer: MyZpmInstall
    private lateinit var feedbackMessages: MutableList<String>

    @BeforeEach
    fun setUp() {
        workspace = Files.createTempDirectory("zpm-install-test")
        feedbackMessages = mutableListOf()
        installer = MyZpmInstall(
            cache = object : ZpmCacheKt(emptyList(), workspace) {
                override fun resolveImports(
                    imports: List<ZpmDependencyKt>,
                    dependencies: List<ZpmDependencyKt>
                ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = Either.Right(emptyList())
            },
            installDir = workspace.resolve("install").createDirectories(),
            jarCopier = JarCopier(dryRun = true, feedback = { feedbackMessages.add(it) }),
            manifestMerger = ManifestMerger(dryRun = true, feedback = { feedbackMessages.add(it) }),
            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = { feedbackMessages.add(it) }),
            imageLinker = DefaultImageLinker(dryRun = true, feedback = { feedbackMessages.add(it) }),
            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = { feedbackMessages.add(it) }),
            dryRun = true,
            verbose = true,
            feedback = { feedbackMessages.add(it) }
        )
    }

    @AfterEach
    fun tearDown() {
        Files.walk(workspace)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun `should migrate unnamed and automatic modules`() {
        val dep1 = createDummyJar("zilla-core-0.9.0.jar", workspace, "com/example/CoreDummy.class")
        val dep2 = createDummyJar("zilla-binding-mqtt-0.9.0.jar", workspace, "com/example/MqttDummy.class")
        val artifacts = listOf(
            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet()),
            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0"), dep2, emptySet())
        )

        installer = object : MyZpmInstall(
            cache = object : ZpmCacheKt(emptyList(), workspace) {
                override fun resolveImports(
                    imports: List<ZpmDependencyKt>,
                    dependencies: List<ZpmDependencyKt>
                ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = Either.Right(artifacts)
            },
            installDir = workspace.resolve("install").createDirectories(),
            jarCopier = JarCopier(dryRun = true, feedback = { feedbackMessages.add(it) }),
            manifestMerger = ManifestMerger(dryRun = true, feedback = { feedbackMessages.add(it) }),
            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = { feedbackMessages.add(it) }),
            imageLinker = DefaultImageLinker(dryRun = true, feedback = { feedbackMessages.add(it) }),
            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = { feedbackMessages.add(it) }),
            dryRun = true,
            verbose = true,
            feedback = { feedbackMessages.add(it) }
        ) {
             override fun discoverModules(
                artifacts: List<ZpmArtifactKt>,
                feedback: ((String) -> Unit)?
            ): List<ZpmModuleKt> {
                feedback?.invoke("MIKE discovered ${artifacts.size} modules")
                return artifacts.map { artifact ->
                    ZpmModuleKt(
                        name = if (artifact.id.artifactId == "zilla-core") "zilla.core" else "zilla.binding.mqtt",
                        id = artifact.id,
                        paths = mutableSetOf(artifact.path),
                        delegating = false,
                        automatic = artifact.id.artifactId == "zilla-binding-mqtt"
                    )
                }
            }
            fun migrateUnnamed(
                modules: List<ZpmModuleKt>,
                delegate: ZpmModuleKt
            ): Either<NonEmptyList<String>, List<ZpmModuleKt>> {
                val unnamed = modules.filter { it.name == null }
                delegate.paths.addAll(unnamed.flatMap { it.paths })
                feedbackMessages.add("MIKE migrated ${unnamed.size} unnamed modules to delegate")
                return Either.Right(modules.filter { it.name != null })
            }

             fun delegateAutomatic(
                modules: List<ZpmModuleKt>,
                delegate: ZpmModuleKt
            ): Either<NonEmptyList<String>, List<ZpmModuleKt>> {
                val automatic = modules.filter { it.automatic }
                delegate.paths.addAll(automatic.flatMap { it.paths })
                feedbackMessages.add("MIKE delegated ${automatic.size} automatic modules")
                return Either.Right(modules.filter { !it.automatic })
            }
        }

        val result = installer.discoverAndMigrateModules(artifacts, feedbackMessages::add)

        assertTrue(result.isRight(), "Should succeed, but got: ${result.leftOrNull()}")
        val delegate = result.getOrNull()!!
        assertEquals(1, delegate.paths.size, "Delegate should contain path from automatic module")
        assertTrue(delegate.paths.contains(dep2), "Delegate should contain zilla-binding-mqtt path")
        assertTrue(feedbackMessages.any { it.contains("MIKE discovered 2 modules") }, "Should log module discovery")
        assertTrue(feedbackMessages.any { it.contains("MIKE migrated 0 unnamed modules to delegate") }, "Should log no unnamed migration")
//        assertTrue(feedbackMessages.any { it.contains("MIKE delegated 1 automatic modules") }, "Should log automatic delegation")
    }

    @Test
    fun `should fail when no modules found`() {
        val artifacts = emptyList<ZpmArtifactKt>()

        installer = object : MyZpmInstall(
            cache = object : ZpmCacheKt(emptyList(), workspace) {
                override fun resolveImports(
                    imports: List<ZpmDependencyKt>,
                    dependencies: List<ZpmDependencyKt>
                ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = Either.Right(artifacts)
            },
            installDir = workspace.resolve("install").createDirectories(),
            jarCopier = JarCopier(dryRun = true, feedback = { feedbackMessages.add(it) }),
            manifestMerger = ManifestMerger(dryRun = true, feedback = { feedbackMessages.add(it) }),
            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = { feedbackMessages.add(it) }),
            imageLinker = DefaultImageLinker(dryRun = true, feedback = { feedbackMessages.add(it) }),
            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = { feedbackMessages.add(it) }),
            dryRun = true,
            verbose = true,
            feedback = { feedbackMessages.add(it) }
        ) {
           override fun discoverModules(
                artifacts: List<ZpmArtifactKt>,
                feedback: ((String) -> Unit)?
            ): List<ZpmModuleKt> = emptyList()
        }

        val result = installer.discoverAndMigrateModules(artifacts, feedbackMessages::add)

        assertTrue(result.isLeft(), "Should fail with NoModulesFound")
        assertEquals(
            ZpmResolutionErrorKt.NoModulesFound("No modules found to create JAR"),
            result.leftOrNull(),
            "Should return NoModulesFound"
        )
        assertTrue(feedbackMessages.any { it.contains("MIKE discovered 0 modules") }, "Should log no modules discovered")
    }

    @Test
    fun `should fail on migration errors`() {
        val dep1 = createDummyJar("zilla-core-0.9.0.jar", workspace, "com/example/CoreDummy.class")
        val artifacts = listOf(
            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet())
        )

        installer = object : MyZpmInstall(
            cache = object : ZpmCacheKt(emptyList(), workspace) {
                override fun resolveImports(
                    imports: List<ZpmDependencyKt>,
                    dependencies: List<ZpmDependencyKt>
                ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = Either.Right(artifacts)
            },
            installDir = workspace.resolve("install").createDirectories(),
            jarCopier = JarCopier(dryRun = true, feedback = { feedbackMessages.add(it) }),
            manifestMerger = ManifestMerger(dryRun = true, feedback = { feedbackMessages.add(it) }),
            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = { feedbackMessages.add(it) }),
            imageLinker = DefaultImageLinker(dryRun = true, feedback = { feedbackMessages.add(it) }),
            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = { feedbackMessages.add(it) }),
            dryRun = true,
            verbose = true,
            feedback = { feedbackMessages.add(it) }
        ) {
            override fun discoverModules(
                artifacts: List<ZpmArtifactKt>,
                feedback: ((String) -> Unit)?
            ): List<ZpmModuleKt> = listOf(
                ZpmModuleKt(
                    name = null,
                    id = ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"),
                    paths = mutableSetOf(dep1),
                    delegating = false,
                    automatic = false
                )
            )

             fun migrateUnnamed(
                modules: List<ZpmModuleKt>,
                delegate: ZpmModuleKt
            ): Either<NonEmptyList<String>, List<ZpmModuleKt>> =
                Either.Left(NonEmptyList("Migration failed for zilla-core", emptyList()))
        }

        val result = installer.discoverAndMigrateModules(artifacts, feedbackMessages::add)

        assertTrue(result.isLeft(), "Should fail with migration error")
        assertEquals(
            ZpmResolutionErrorKt.DependencyResolutionError("Migration failed for zilla-core"),
            result.leftOrNull(),
            "Should return DependencyResolutionError"
        )
        assertTrue(feedbackMessages.any { it.contains("MIKE discovered 1 modules") }, "Should log module discovery")
        assertTrue(feedbackMessages.any { it.contains("❌ Migration errors: Migration failed for zilla-core") }, "Should log migration error")
    }

    @Test
    fun `should fail on delegation errors`() {
        val dep1 = createDummyJar("zilla-core-0.9.0.jar", workspace, "com/example/CoreDummy.class")
        val artifacts = listOf(
            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet())
        )

        installer = object : MyZpmInstall(
            cache = object : ZpmCacheKt(emptyList(), workspace) {
                override fun resolveImports(
                    imports: List<ZpmDependencyKt>,
                    dependencies: List<ZpmDependencyKt>
                ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = Either.Right(artifacts)
            },
            installDir = workspace.resolve("install").createDirectories(),
            jarCopier = JarCopier(dryRun = true, feedback = { feedbackMessages.add(it) }),
            manifestMerger = ManifestMerger(dryRun = true, feedback = { feedbackMessages.add(it) }),
            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = { feedbackMessages.add(it) }),
            imageLinker = DefaultImageLinker(dryRun = true, feedback = { feedbackMessages.add(it) }),
            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = { feedbackMessages.add(it) }),
            dryRun = true,
            verbose = true,
            feedback = { feedbackMessages.add(it) }
        ) {
             override fun discoverModules(
                artifacts: List<ZpmArtifactKt>,
                feedback: ((String) -> Unit)?
            ): List<ZpmModuleKt> = listOf(
                ZpmModuleKt(
                    name = "zilla.core",
                    id = ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"),
                    paths = mutableSetOf(dep1),
                    delegating = false,
                    automatic = true
                )
            )

             fun migrateUnnamed(
                modules: List<ZpmModuleKt>,
                delegate: ZpmModuleKt
            ): Either<NonEmptyList<String>, List<ZpmModuleKt>> = Either.Right(modules)

             fun delegateAutomatic(
                modules: List<ZpmModuleKt>,
                delegate: ZpmModuleKt
            ): Either<NonEmptyList<String>, List<ZpmModuleKt>> =
                Either.Left(NonEmptyList("Delegation failed for zilla-core", emptyList()))
        }

        val result = installer.discoverAndMigrateModules(artifacts, feedbackMessages::add)

        assertTrue(result.isLeft(), "Should fail with delegation error")
        assertEquals(
            ZpmResolutionErrorKt.DependencyResolutionError("Delegation failed for zilla-core"),
            result.leftOrNull(),
            "Should return DependencyResolutionError"
        )
        assertTrue(feedbackMessages.any { it.contains("MIKE discovered 1 modules") }, "Should log module discovery")
        assertTrue(feedbackMessages.any { it.contains("MIKE migrated 0 unnamed modules to delegate") }, "Should log no unnamed migration")
        assertTrue(feedbackMessages.any { it.contains("❌ Delegation errors: Delegation failed for zilla-core") }, "Should log delegation error")
    }

    private fun createDummyJar(name: String, targetDir: Path, className: String): Path {
        val p = targetDir.resolve(name)
        val manifest = Manifest().apply {
            mainAttributes.putValue("Manifest-Version", "1.0")
            mainAttributes.putValue("Created-By", "Zilla Manager Test")
            if (name.contains("binding-mqtt")) {
                mainAttributes.putValue("Automatic-Module-Name", "zilla.binding.mqtt")
            }
        }
        JarOutputStream(Files.newOutputStream(p), manifest).use { out ->
            out.putNextEntry(JarEntry("META-INF/"))
            out.closeEntry()
            out.putNextEntry(JarEntry(className))
            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            out.closeEntry()
            out.flush()
            out.finish()
        }
        return p
    }
}