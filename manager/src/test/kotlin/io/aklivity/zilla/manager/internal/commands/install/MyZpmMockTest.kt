import arrow.core.Either
import io.aklivity.zilla.manager.internal.commands.install.MyZpmInstall
import io.aklivity.zilla.manager.internal.commands.install.cache.*
import io.aklivity.zilla.manager.internal.commands.install.impl.JarCopier
import io.aklivity.zilla.manager.internal.commands.install.impl.ManifestMerger
import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.writeText

class MyZpmInstallTest {

    @Test
    fun `should install from template and produce all expected outputs`() {
        val tempDir = Files.createTempDirectory("zpm-install-test")
        val templatePath = tempDir.resolve("zpm.json")
        Files.writeString(templatePath, "{}")

        // Fake artifacts
        val fakeArtifactPath = tempDir.resolve("fake.jar").apply { writeText("jar-data") }
        val fakeArtifactId = ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0")
        val fakeArtifacts = mapOf(fakeArtifactId to ZpmArtifactKt(fakeArtifactId, fakeArtifactPath, emptySet()))

        // Fake cache
        val fakeCache = object : ZpmCacheKt(emptyList(), tempDir) {
            override fun resolveImports(imports: List<ZpmDependencyKt>) =
                Either.Right(fakeArtifacts)
        }

        // Fake copier
        val fakeJarCopier = object : JarCopier(false) {
            override fun copyJars(inputJars: List<Path>, outputJar: Path) =
                Either.Right(outputJar.apply {
                    Files.createDirectories(parent)
                    writeText("copied-jar")
                })
        }

        // Fake merger
        val fakeManifestMerger = object : ManifestMerger(false) {
            override fun merge(inputJars: List<Path>, outputJar: Path) =
                Either.Right(outputJar.apply {
                    // Append something to show merge happened
                    Files.writeString(this, Files.readString(this) + "-merged")
                })
        }

        // Fake module-info generator
        val fakeModuleInfoGenerator = object : ModuleInfoGenerator() {
            override fun generate(inputJar: Path, outputDir: Path) =
                Either.Right(outputDir.resolve("module-info.java").apply {
                    Files.createDirectories(parent)
                    writeText("module info")
                })
        }

        val installer = MyZpmInstall(
            cache = fakeCache,
            installDir = tempDir,
            jarCopier = fakeJarCopier,
            manifestMerger = fakeManifestMerger,
            moduleInfoGenerator = fakeModuleInfoGenerator,
            dryRun = false,
            verbose = true
        )

        val result = installer.installFromTemplate(templatePath)

        assertTrue(result.isRight(), "Install should succeed")

        // 1. Jar copy check
        val installedJar = tempDir.resolve("zilla-install.jar")
        assertTrue(Files.exists(installedJar), "zilla-install.jar should exist")
        assertTrue(Files.readString(installedJar).contains("copied-jar"), "Jar should contain copied marker")
        assertTrue(Files.readString(installedJar).contains("-merged"), "Jar should be marked merged")

        // 2. Module-info check
        val moduleInfoPath = tempDir.resolve("module-info/module-info.java")
        assertTrue(Files.exists(moduleInfoPath), "module-info.java should exist")
        assertEquals("module info", Files.readString(moduleInfoPath))

        // 3. Lock file check
        val lockFile = tempDir.resolve("zpm.lock")
        assertTrue(Files.exists(lockFile), "zpm.lock should exist")
        val lockContents = Files.readString(lockFile)
        assertTrue(lockContents.contains(fakeArtifactId.toString()), "Lock file should contain artifact ID")
    }

    @Test
    fun `should fail when cache resolution fails`() {
        val tempDir = Files.createTempDirectory("zpm-install-fail-test")
        val templatePath = tempDir.resolve("zpm.json")
        Files.writeString(templatePath, "{}")

        val fakeCache = object : ZpmCacheKt(emptyList(), tempDir) {
            override fun resolveImports(imports: List<ZpmDependencyKt>) =
                Either.Left(ZpmResolutionErrorKt.ArtifactNotFound("fake"))
        }

        val installer = MyZpmInstall(
            cache = fakeCache,
            installDir = tempDir,
            jarCopier = JarCopier(false),
            manifestMerger = ManifestMerger(false),
            moduleInfoGenerator = ModuleInfoGenerator(),
            dryRun = false,
            verbose = false
        )

        val result = installer.installFromTemplate(templatePath)
        assertTrue(result.isLeft(), "Install should fail when cache fails")
    }
}