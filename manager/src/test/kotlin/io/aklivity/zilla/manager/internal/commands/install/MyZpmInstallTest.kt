package io.aklivity.zilla.manager.internal.commands.install

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertTrue
import com.github.ajalt.clikt.core.*

class MyZpmInstallTest {

    private fun createDummyJar(path: Path, entries: Map<String, String> = mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0")): Path {
        Files.createDirectories(path.parent)
        java.util.jar.JarOutputStream(Files.newOutputStream(path)).use { jarOut ->
            for ((name, content) in entries) {
                val entry = java.util.jar.JarEntry(name)
                jarOut.putNextEntry(entry)
                jarOut.write(content.toByteArray())
                jarOut.closeEntry()
            }
        }
        return path
    }

    @Test
    fun `should install jars and show success`() {
        // Redirect output
        val outContent = ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(PrintStream(outContent))

        try {
            val tempDir = Files.createTempDirectory("zpm-install-test")
            val jar1 = createDummyJar(tempDir.resolve("lib1.jar"))
            val jar2 = createDummyJar(tempDir.resolve("lib2.jar"))
            val outputJar = tempDir.resolve("final.jar")

            val args = listOf(
                jar1.toString(),
                jar2.toString(),
                "--output", outputJar.toString(),
                "--verbose"
            )

            MyZpmInstall().main(args)

            val output = outContent.toString()

            assertTrue(output.contains("✅ Successfully installed to"))
            assertTrue(Files.exists(outputJar))
        } finally {
            // Restore output
            System.setOut(oldOut)
        }
    }

    @Test
    fun `should show dry-run messages`() {
        val outContent = ByteArrayOutputStream()
        val oldOut = System.out
        System.setOut(PrintStream(outContent))

        try {
            val tempDir = Files.createTempDirectory("zpm-install-dryrun")
            val jar1 = createDummyJar(tempDir.resolve("lib1.jar"))
            val outputJar = tempDir.resolve("dryrun-final.jar")

            val args = listOf(
                jar1.toString(),
                "--output", outputJar.toString(),
                "--dry-run",
                "--verbose"
            )

            MyZpmInstall().main(args)

            val output = outContent.toString()

            assertTrue(output.contains("[dry-run]"))
            assertTrue(output.contains("Would copy into"))
        } finally {
            System.setOut(oldOut)
        }
    }
}
