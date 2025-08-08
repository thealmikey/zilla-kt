//package io.aklivity.zilla.manager.internal.commands.install
//
//import arrow.core.Either
//import io.aklivity.zilla.manager.internal.commands.install.cache.*
//import org.junit.jupiter.api.*
//import org.slf4j.LoggerFactory
//import java.nio.file.Files
//import java.nio.file.Path
//import kotlin.io.path.*
//
//class MyZpmInstallExpandedTest {
//    private lateinit var tempDir: Path
//    private lateinit var installDir: Path
//    private val logger = LoggerFactory.getLogger(MyZpmInstallExpandedTest::class.java)
//
//    @BeforeEach
//    fun setup() {
//        tempDir = Files.createTempDirectory("zpm-template")
//        installDir = Files.createTempDirectory("zpm-install")
//    }
//
//    @AfterEach
//    fun cleanup() {
//        tempDir.toFile().deleteRecursively()
//        installDir.toFile().deleteRecursively()
//    }
//
//    @Test
//    fun `should install multiple artifacts from real-like template`() {
//        // --- 1) Create fake artifact files ---
//        val fakeArtifacts = listOf(
//            fakeArtifact("io.aklivity", "zilla-core", "0.9.0"),
//            fakeArtifact("io.aklivity", "zilla-binding-mqtt", "0.9.0"),
//            fakeArtifact("io.aklivity", "zilla-binding-http", "0.9.0")
//        )
//
//        val fakeArtifactMap = fakeArtifacts.associateBy { it.id }
//
//        // --- 2) Fake ZpmCacheKt ---
//        val fakeCache = object : ZpmCacheKt(emptyList(), tempDir) {
//            override fun resolveImports(
//                imports: List<ZpmDependencyKt>
//            ): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> {
//                logger.info("Fake resolving: {}", imports)
//                return Either.Right(fakeArtifactMap)
//            }
//        }
//
//        // --- 3) Create a real-like zpm.json ---
//        val templatePath = tempDir.resolve("zpm.json")
//        templatePath.writeText(
//            """
//            {
//              "repositories": [
//                "https://maven.packages.aklivity.io/",
//                "https://repo.maven.apache.org/maven2/"
//              ],
//              "imports": [
//                "io.aklivity:zilla-core:0.9.0",
//                "io.aklivity:zilla-binding-mqtt:0.9.0",
//                "io.aklivity:zilla-binding-http:0.9.0"
//              ],
//              "dependencies": []
//            }
//            """.trimIndent()
//        )
//
//        // --- 4) Run installer ---
//        val installer = MyZpmInstall(fakeCache, installDir)
//        val result = installer.installFromTemplate(templatePath)
//
//        // --- 5) Assertions ---
//        Assertions.assertTrue(result.isRight(), "Expected Right for install")
//        fakeArtifacts.forEach { artifact ->
//            val installedFile = installDir.resolve(artifact.path.fileName)
//            Assertions.assertTrue(installedFile.exists(), "Expected ${installedFile.fileName} to be installed")
//            Assertions.assertEquals("fake-${artifact.id.artifactId}", installedFile.readText())
//        }
//    }
//
//    private fun fakeArtifact(group: String, artifact: String, version: String): ZpmArtifactKt {
//        val fakeFile = tempDir.resolve("$artifact-$version.jar")
//        fakeFile.writeText("fake-$artifact")
//        return ZpmArtifactKt(
//            id = ZpmArtifactIdKt(group, artifact, version),
//            path = fakeFile,
//            dependencies = emptySet()
//        )
//    }
//}
