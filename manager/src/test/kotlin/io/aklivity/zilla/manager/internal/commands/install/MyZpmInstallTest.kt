//package io.aklivity.zilla.manager.internal.commands.install
//
//import arrow.core.Either
//import io.aklivity.zilla.manager.internal.commands.install.cache.*
//import io.aklivity.zilla.manager.internal.commands.install.impl.JarCopier
//import io.aklivity.zilla.manager.internal.commands.install.impl.ManifestMerger
//import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
//import org.junit.jupiter.api.Test
//import java.nio.file.*
//import kotlin.io.path.*
//import kotlin.test.assertTrue
//
//class MyZpmInstallTest {
//
//    @Test
//    fun `should run full install pipeline in dry run`() {
//        // Setup temp workspace
//        val tempDir = Files.createTempDirectory("zpm-install-test")
//        val installDir = tempDir.resolve("install")
//        Files.createDirectories(installDir)
//
//        // Fake dependencies
//        val dep1 = createDummyJar("zilla-core-0.9.0.jar")
//        val dep2 = createDummyJar("zilla-binding-mqtt-0.9.0.jar")
//
//        val fakeCache = object : ZpmCacheKt(
//            repositories = emptyList(),
//            localCacheDir = tempDir
//        ) {
//            override fun resolveImports(
//                imports: List<ZpmDependencyKt>
//            ): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> {
//                val map = mapOf(
//                    ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0") to
//                            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-core", "0.9.0"), dep1, emptySet()),
//                    ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0") to
//                            ZpmArtifactKt(ZpmArtifactIdKt("io.aklivity", "zilla-binding-mqtt", "0.9.0"), dep2, emptySet())
//                )
//                return Either.Right(map)
//            }
//        }
//
//        val jarCopier = JarCopier(dryRun = true)
//        val manifestMerger = ManifestMerger(dryRun = true)
//        val moduleInfoGenerator = ModuleInfoGenerator(dryRun = true)
//
//        val installer = MyZpmInstall(
//            cache = fakeCache,
//            installDir = installDir,
//            jarCopier = jarCopier,
//            manifestMerger = manifestMerger,
//            moduleInfoGenerator = moduleInfoGenerator,
//            dryRun = true,
//            verbose = true
//        )
//
//        // Create a fake template file
//        val templatePath = tempDir.resolve("zpm.json")
//        Files.writeString(templatePath, """{"imports": ["io.aklivity:zilla-core:0.9.0", "io.aklivity:zilla-binding-mqtt:0.9.0"]}""")
//
//        val result = installer.installFromTemplate(templatePath)
//
//        assertTrue(result.isRight(), "Expected install to succeed in dry run mode")
//
//        // Verify lock file
//        val lockFile = installDir.resolve("zpm.lock")
//        assertTrue(lockFile.exists(), "Expected lock file to be written")
//        val lockContent = Files.readString(lockFile)
//        assertTrue(lockContent.contains("zilla-core"))
//        assertTrue(lockContent.contains("zilla-binding-mqtt"))
//
//        // Verify module-info.java path exists
//// Verify module-info.java path exists
//        val moduleInfoPath = installDir.resolve("module-info/zilla-install/module-info.java")
//        assertTrue(moduleInfoPath.exists(), "Expected module-info.java to be generated in dry run")
//
//    }
//
//    private fun createDummyJar(name: String): Path {
//        val tempDir = Files.createTempDirectory("zpm-dummy-jar")
//        val jarPath = tempDir.resolve(name)
//        java.util.jar.JarOutputStream(Files.newOutputStream(jarPath)).use { out ->
//            val entry = java.util.jar.JarEntry("com/example/Dummy.class")
//            out.putNextEntry(entry)
//            out.write(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
//            out.closeEntry()
//        }
//        return jarPath
//    }
//}
