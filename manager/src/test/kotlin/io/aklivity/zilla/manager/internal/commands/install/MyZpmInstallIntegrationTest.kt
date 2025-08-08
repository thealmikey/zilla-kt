package io.aklivity.zilla.manager.internal.commands.install

import arrow.core.Either
import io.aklivity.zilla.manager.internal.commands.install.cache.*
import io.aklivity.zilla.manager.internal.commands.install.impl.JarCopier
import io.aklivity.zilla.manager.internal.commands.install.impl.ManifestMerger
import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.*
import kotlin.test.assertTrue

class MyZpmInstallIntegrationTest {

    @Test
    fun `full pipeline from real zpm json in dry run`() {
        val workspace = Files.createTempDirectory("zpm-install-int")
        val installDir = workspace.resolve("install")
        Files.createDirectories(installDir)

        // create two dummy jars that would be "resolved" by the fake cache
        val dep1 = createDummyJar("zilla-core-0.9.0.jar")
        val dep2 = createDummyJar("zilla-binding-mqtt-0.9.0.jar")

        val fakeArtifacts = mapOf(
            ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0") to ZpmArtifactKt(
                ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet()
            ),
            ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0") to ZpmArtifactKt(
                ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0"), dep2, emptySet()
            )
        )

        val fakeCache = object : ZpmCacheKt(emptyList(), workspace) {
            override fun resolveImports(imports: List<ZpmDependencyKt>) =
                Either.Right(fakeArtifacts)
        }

        val jarCopier = JarCopier(dryRun = true)
        val manifestMerger = ManifestMerger(dryRun = true)
        val moduleInfoGenerator = ModuleInfoGenerator(dryRun = true)

        val installer = MyZpmInstall(
            cache = fakeCache,
            installDir = installDir,
            jarCopier = jarCopier,
            manifestMerger = manifestMerger,
            moduleInfoGenerator = moduleInfoGenerator,
            dryRun = true,
            verbose = true
        )

        // Write a real zpm.json
        val template = ZpmTemplate(
            repositories = listOf("https://repo.maven.apache.org/maven2"),
            imports = listOf("io.aklivity:zilla-core:0.9.0", "io.aklivity:zilla-binding-mqtt:0.9.0"),
            dependencies = emptyList()
        )
        val json = Json { prettyPrint = true }
        val templatePath = workspace.resolve("zpm.json")
        json.encodeToFile(templatePath, template, pretty = true)

        val result = installer.installFromTemplate(templatePath)

        assertTrue(result.isRight(), "Install should succeed in dry-run")
        // lock file
        val lockFile = installDir.resolve("zpm.lock")
        assertTrue(lockFile.exists(), "zpm.lock should be written")
        val content = Files.readString(lockFile)
        assertTrue(content.contains("zilla-core"))
        assertTrue(content.contains("zilla-binding-mqtt"))

        // module-info path (dry-run created placeholder)
        val moduleInfoPath = installDir.resolve("module-info/zilla-install/module-info.java")
        assertTrue(moduleInfoPath.exists(), "module-info should be created in dry run")
    }

    private fun createDummyJar(name: String) = run {
        val tmp = Files.createTempDirectory("zpm-dummy")
        val p = tmp.resolve(name)
        java.util.jar.JarOutputStream(Files.newOutputStream(p)).use { out ->
            out.putNextEntry(java.util.jar.JarEntry("com/example/Dummy.class"))
            out.write(byteArrayOf(0xCA.toByte().toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
            out.closeEntry()
        }
        p
    }
}
