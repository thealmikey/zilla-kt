package io.aklivity.zilla.manager.internal.commands.install

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.arguments.*
import com.github.ajalt.clikt.parameters.options.*
import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.impl.*
import java.nio.file.Path
import java.nio.file.Paths
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.core.main

class MyZpmInstall : CliktCommand(name = "zpm-install") {

    val inputJars by argument()
        .multiple()
   //     .help("JARs to merge")

    val output by option("--output", "-o")
        .convert { Paths.get(it) }
        .required()
        .help("Output JAR path")

    val dryRun by option("--dry-run")
        .flag()
        .help("Only show what would happen")

    val verbose by option("--verbose")
        .flag()
        .help("Print extra details")

    override fun run() {
        val feedback: (String) -> Unit = { if (verbose) echo(it) }

        val copier = JarCopier(dryRun, feedback)
        val merger = ManifestMerger(dryRun, feedback)

        val result = copier.copyJars(
            inputJars = inputJars.map { Paths.get(it) },
            outputJar = output
        ).flatMap { mergedJar ->
            merger.merge(listOf(mergedJar),mergedJar)
        }

        result.fold(
            ifLeft = { err ->
                echo("❌ $err")
            },
            ifRight = { final ->
                echo("✅ Successfully installed to: $final")
            }
        )
    }
}
