//package io.aklivity.zilla.manager.internal.commands.install
//
//import arrow.core.Either
//import arrow.core.left
//import arrow.core.right
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactIdKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmCacheKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmDependencyKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
//import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultImageLinker
//import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultLauncherWriter
//import io.aklivity.zilla.manager.internal.commands.install.impl.JarCopier
//import io.aklivity.zilla.manager.internal.commands.install.impl.ManifestMerger
//import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
//import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
//import kotlinx.serialization.encodeToString
//import kotlinx.serialization.json.Json
//import org.junit.jupiter.api.AfterEach
//import org.junit.jupiter.api.BeforeEach
//import org.junit.jupiter.api.Test
//import java.nio.file.Files
//import java.nio.file.Path
//import java.util.Comparator
//import java.util.jar.JarEntry
//import kotlin.io.path.createDirectories
//import kotlin.io.path.exists
//import kotlin.io.path.readText
//import kotlin.test.assertEquals
//import kotlin.test.assertFalse
//import kotlin.test.assertIs
//import kotlin.test.assertTrue
//
//class MyZpmInstallTest {
//
//    private lateinit var workspace: Path
//    private lateinit var installDir: Path
//    private val json = Json { prettyPrint = true }
//    private val feedbackMessages = mutableListOf<String>()
//    private val feedback: (String) -> Unit = { feedbackMessages.add(it) }
//
//    @BeforeEach
//    fun setUp() {
//        workspace = Files.createTempDirectory("zpm-install-test")
//        installDir = workspace.resolve("install")
//        installDir.createDirectories()
//    }
//
//    @AfterEach
//    fun tearDown() {
//        Files.walk(workspace)
//            .sorted(Comparator.reverseOrder())
//            .forEach { Files.deleteIfExists(it) }
//    }
//
//    @Test
//    fun `parseTemplate should parse valid template`() {
//        val template = ZpmTemplate(
//            repositories = listOf("https://repo.maven.apache.org/maven2"),
//            imports = listOf("io.aklivity:zilla-core:0.9.0"),
//            dependencies = emptyList()
//        )
//        val templatePath = workspace.resolve("valid.json")
//        Files.writeString(templatePath, json.encodeToString(template))
//
//        val installer = createInstaller()
//        val result = installer.parseTemplate(templatePath, feedback)
//
//        assertTrue(result.isRight())
//        val parsed = result.getOrNull()
//        assertEquals(template.imports, parsed?.imports)
//        assertTrue(feedbackMessages.any { it.contains("Parsing template") })
//    }
//
//    @Test
//    fun `parseTemplate should fail on invalid JSON`() {
//        val templatePath = workspace.resolve("invalid.json")
//        Files.writeString(templatePath, "{ invalid json }")
//
//        val installer = createInstaller()
//        val result = installer.parseTemplate(templatePath, feedback)
//
//        assertTrue(result.isLeft())
//        assertIs<ZpmResolutionErrorKt.DependencyResolutionError>(result.leftOrNull())
//        assertTrue(feedbackMessages.any { it.contains("Failed to parse template") })
//    }
//
//    @Test
//    fun `resolveDependencies should resolve valid dependencies`() {
//        val template = ZpmTemplate(
//            imports = listOf("io.aklivity:zilla-core:0.9.0"),
//            dependencies = emptyList()
//        )
//        val fakeCache = createFakeCache(listOf(
//            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), createDummyJar("zilla-core-0.9.0.jar"), emptySet())
//        ))
//
//        val installer = createInstaller(cache = fakeCache)
//        val result = installer.resolveDependencies(template, feedback)
//
//        assertTrue(result.isRight())
//        assertEquals(1, result.getOrNull()?.size)
//        assertTrue(feedbackMessages.any { it.contains("Resolving dependencies via cache") })
//    }
//
//    @Test
//    fun `resolveDependencies should handle resolution failure`() {
//        val template = ZpmTemplate(imports = listOf("invalid:dep:1.0"), dependencies = emptyList())
//        val fakeCache = createFakeCache(emptyList()) { ZpmResolutionErrorKt.DependencyResolutionError("Failed").left() }
//
//        val installer = createInstaller(cache = fakeCache)
//        val result = installer.resolveDependencies(template, feedback)
//
//        assertTrue(result.isLeft())
//        assertTrue(feedbackMessages.any { it.contains("Dependency resolution failed") })
//    }
//
//    @Test
//    fun `installFromTemplate should succeed in dry run mode`() {
//        val template = ZpmTemplate(
//            imports = listOf("io.aklivity:zilla-core:0.9.0"),
//            dependencies = emptyList()
//        )
//        val templatePath = workspace.resolve("zpm.json")
//        Files.writeString(templatePath, json.encodeToString(template))
//
//        val fakeCache = createFakeCache(listOf(
//            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), createDummyJar("zilla-core-0.9.0.jar"), emptySet())
//        ))
//        val installer = createInstaller(
//            cache = fakeCache,
//            dryRun = true,
//            jarCopier = JarCopier(dryRun = true, feedback = feedback),
//            manifestMerger = ManifestMerger(dryRun = true, feedback = feedback),
//            moduleInfoGenerator = ModuleInfoGenerator(dryRun = true, feedback = feedback),
//            imageLinker = DefaultImageLinker(dryRun = true, feedback = feedback),
//            launcherWriter = DefaultLauncherWriter(dryRun = true, feedback = feedback)
//        )
//
//        val result = installer.installFromTemplate(templatePath, feedback)
//
//        assertTrue(result.isRight(), "Install should succeed in dry-run: ${result.leftOrNull()}")
//        assertTrue(feedbackMessages.any { it.contains("[dry-run]") })
//
//        val lockFile = installDir.resolve("zpm.lock")
//        assertFalse(lockFile.exists(), "No real files created in dry-run")
//    }
//
//    @Test
//    fun `generateAndCompileModuleInfo should succeed in dry run`() {
//        val targetJar = createDummyJar("target.jar")
//        val fakeModuleInfoGenerator = object : ModuleInfoGenerator(dryRun = true, feedback = feedback) {
//            override fun generate(targetJar: Path, installDir: Path, module: ZpmModuleKt): Either<ZpmResolutionErrorKt, Path> {
//                val path = installDir.resolve("module-info.java")
//                Files.createDirectories(path.parent)
//                Files.writeString(path, "// Dry-run module-info")
//                return path.right()
//            }
//        }
//        val installer = createInstaller(
//            dryRun = true,
//            moduleInfoGenerator = fakeModuleInfoGenerator,
//            manifestMerger = ManifestMerger(dryRun = true, feedback = feedback)
//        )
//
//        val result = installer.generateAndCompileModuleInfo(targetJar, feedback)
//
//        assertTrue(result.isRight())
//        assertTrue(feedbackMessages.any { it.contains("Generating module-info.java") })
//        assertTrue(feedbackMessages.any { it.contains("Compiling module-info.java") })
//    }
//
//    @Test
//    fun `installFromTemplate should handle invalid artifact paths`() {
//        val template = ZpmTemplate(
//            imports = listOf("io.aklivity:zilla-core:0.9.0"),
//            dependencies = emptyList()
//        )
//        val templatePath = workspace.resolve("zpm.json")
//        Files.writeString(templatePath, json.encodeToString(template))
//        val fakeCache = createFakeCache(listOf(
//            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), workspace.resolve("missing.jar"), emptySet())
//        ))
//
//        val installer = createInstaller(cache = fakeCache, dryRun = true)
//        val result = installer.installFromTemplate(templatePath, feedback)
//
//        assertTrue(result.isLeft())
//        assertTrue(feedbackMessages.any { it.contains("is invalid or unreadable") })
//    }
//
//    @Test
//    fun `installFromTemplate should handle corrupt artifact paths`() {
//        val template = ZpmTemplate(
//            imports = listOf("io.aklivity:zilla-core:0.9.0"),
//            dependencies = emptyList()
//        )
//        val templatePath = workspace.resolve("zpm.json")
//        Files.writeString(templatePath, json.encodeToString(template))
//        val corruptJar = workspace.resolve("corrupt.jar")
//        Files.write(corruptJar, "not a jar".toByteArray())
//        val fakeCache = createFakeCache(listOf(
//            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), corruptJar, emptySet())
//        ))
//
//        val installer = createInstaller(cache = fakeCache)
//        val result = installer.installFromTemplate(templatePath, feedback)
//
//        assertTrue(result.isLeft())
//        assertTrue(feedbackMessages.any { it.contains("Error generating module-info") })
//    }
//
//    @Test
//    fun `generateDelegating should succeed in dry run`() {
//        val module = ZpmModuleKt(
//            name = "zilla.delegate",
//            paths = mutableSetOf(createDummyJar("delegate.jar")),
//            delegating = true
//        )
//        val fakeModuleInfoGenerator = object : ModuleInfoGenerator(dryRun = true, feedback = feedback) {
//            override fun generateDelegate(delegate: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
//                val path = outputDir.resolve("module-info/zilla-install/module-info.java")
//                Files.createDirectories(path.parent)
//                Files.writeString(path, "module ${delegate.name} { // Dry-run delegate }")
//                return path.right()
//            }
//        }
//        val installer = createInstaller(
//            dryRun = true,
//            moduleInfoGenerator = fakeModuleInfoGenerator
//        )
//
//        val result = installer.generateDelegating(listOf(module), feedback)
//
//        assertTrue(result.isRight())
//        assertTrue(feedbackMessages.any { it.contains("Generating delegating modules") })
//        assertTrue(feedbackMessages.any { it.contains("[dry-run]") })
//    }
//
//    private fun createInstaller(
//        cache: ZpmCacheKt = createFakeCache(emptyList()),
//        dryRun: Boolean = false,
//        jarCopier: JarCopier = JarCopier(feedback = feedback, dryRun = dryRun),
//        manifestMerger: ManifestMerger = ManifestMerger(feedback = feedback, dryRun = dryRun),
//        moduleInfoGenerator: ModuleInfoGenerator = ModuleInfoGenerator(feedback = feedback, dryRun = dryRun),
//        imageLinker: DefaultImageLinker = DefaultImageLinker(feedback = feedback, dryRun = dryRun),
//        launcherWriter: DefaultLauncherWriter = DefaultLauncherWriter(feedback = feedback, dryRun = dryRun)
//    ): MyZpmInstall {
//        return MyZpmInstall(
//            cache = cache,
//            installDir = installDir,
//            jarCopier = jarCopier,
//            manifestMerger = manifestMerger,
//            moduleInfoGenerator = moduleInfoGenerator,
//            imageLinker = imageLinker,
//            launcherWriter = launcherWriter,
//            dryRun = dryRun,
//            verbose = true,
//            ignoreMissingDependencies = true,
//            feedback = feedback
//        )
//    }
//
//    private fun createDummyJar(name: String): Path {
//        val path = workspace.resolve(name)
//        Files.createDirectories(path.parent)
//        java.util.jar.JarOutputStream(Files.newOutputStream(path)).use { out ->
//            out.putNextEntry(java.util.jar.JarEntry("dummy.class"))
//            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
//            out.closeEntry()
//            out.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
//            out.write("Manifest-Version: 1.0\n".toByteArray())
//            out.closeEntry()
//        }
//        return path
//    }
//
//    private fun createFakeCache(
//        artifacts: List<ZpmArtifactKt>,
//        resolveFunc: (List<ZpmDependencyKt>) -> Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = { artifacts.right() }
//    ): ZpmCacheKt {
//        return object : ZpmCacheKt(emptyList(), workspace) {
//            override fun resolveImports(
//                imports: List<ZpmDependencyKt>,
//                deps: List<ZpmDependencyKt>
//            ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> {
//                return resolveFunc(imports)
//            }
//        }
//    }
//}