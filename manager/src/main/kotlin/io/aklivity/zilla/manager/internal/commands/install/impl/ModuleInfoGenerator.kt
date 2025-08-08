package io.aklivity.zilla.manager.internal.commands.install.impl

import java.nio.file.*
import java.util.spi.ToolProvider
import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.ZpmError

open class ModuleInfoGenerator(
    private val ignoreMissingDeps: Boolean = true,
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) {
    open fun generate(inputJar: Path, outputDir: Path): Either<ZpmError, Path> = Either.catch {
        val jdeps = ToolProvider.findFirst("jdeps")
            .orElseThrow { RuntimeException("jdeps tool not found") }

        Files.createDirectories(outputDir)

        val args = mutableListOf(
            "--generate-module-info", outputDir.toString(),
            inputJar.toString()
        )
        if (ignoreMissingDeps) args.add(0, "--ignore-missing-deps")

        feedback?.invoke("📦 Generating module-info.java for: $inputJar")

        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] jdeps ${args.joinToString(" ")}")

            val generatedPath = outputDir
                .resolve(inputJar.nameWithoutExtension())
                .resolve("module-info.java")

            Files.createDirectories(generatedPath.parent)
            Files.writeString(
                generatedPath,
                "// dry-run module-info for ${inputJar.fileName}"
            )

            feedback?.invoke("📄 [dry-run] Created placeholder module-info at: $generatedPath")

            return@catch generatedPath
        }

        val exitCode = jdeps.run(System.out, System.err, *args.toTypedArray())
        if (exitCode != 0) throw RuntimeException("jdeps failed with exit code $exitCode")

        val generated = outputDir
            .resolve(inputJar.nameWithoutExtension())
            .resolve("module-info.java")

        if (!Files.exists(generated)) {
            throw RuntimeException("module-info.java was not created")
        }

        patchUsesStatements(generated)

        feedback?.invoke("✅ Generated: $generated")
        generated
    }.mapLeft {
        feedback?.invoke("💥 Error generating module-info: ${it.message}")
        ZpmError.PackagingFailed("Failed to generate module-info: ${it.message}")
    }

    private fun patchUsesStatements(moduleInfoPath: Path) {
        val contents = Files.readString(moduleInfoPath)
        val pattern = Regex("""provides\s+(\S+)\s+with""")
        val uses = pattern.findAll(contents).map { "uses ${it.groupValues[1]};" }.toList()

        if (uses.isNotEmpty()) {
            val patched = contents.replace(
                "}",
                uses.joinToString("\n", postfix = "\n}")
            )
            Files.writeString(moduleInfoPath, patched)
            feedback?.invoke("🔧 Patched with uses: ${uses.size} service(s)")
        }
    }

    private fun Path.nameWithoutExtension(): String =
        fileName.toString().removeSuffix(".jar")
}
