package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.*
import java.io.*
import java.nio.file.*
import java.util.jar.*
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import io.aklivity.zilla.manager.internal.commands.install.ModulePackager

class DefaultModulePackager(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) : ModulePackager {

    override fun packageModule(inputJars: List<Path>, output: Path): Either<ZpmError, Path> {
        feedback?.invoke("➡️ Starting packaging to $output")

        if (dryRun) {
            feedback?.invoke("✅ [dry-run] Would package ${inputJars.size} jars into $output")
            return output.right()
        }

        return Either.catch {
            val excludedManifest = Paths.get("META-INF", "MANIFEST.MF")
            val excludedPackage = Paths.get("org", "eclipse", "yasson", "internal", "components")
            val excludedClass = "BeanManagerInstanceCreator"
            val excludedSuffixes = setOf("module-info.class", "package-info.class")
            val servicesPath = Paths.get("META-INF", "services")
            val services = mutableMapOf<String, MutableList<String>>()
            val written = mutableSetOf<String>()

            Files.createDirectories(output.parent)
            JarOutputStream(Files.newOutputStream(output)).use { jarOut ->
                for (jar in inputJars) {
                    JarFile(jar.toFile()).use { jarFile ->
                        val entries = jarFile.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryPath = Paths.get(entry.name)

                            val shouldSkip =
                                entryPath.toString() in excludedSuffixes ||
                                entryPath == excludedManifest ||
                                (entryPath.startsWith(excludedPackage) &&
                                 entryPath.fileName.toString().startsWith(excludedClass))

                            if (shouldSkip) {
                                feedback?.invoke("⏩ Skipped $entryPath")
                                continue
                            }

                            val input = jarFile.getInputStream(entry)
                            if (entryPath.startsWith(servicesPath) &&
                                entryPath.nameCount - servicesPath.nameCount == 1) {

                                val serviceName = servicesPath.relativize(entryPath).toString()
                                val serviceImpls = input.bufferedReader().readLines()
                                services.computeIfAbsent(serviceName) { mutableListOf() }
                                    .addAll(serviceImpls)
                            } else if (written.add(entry.name)) {
                                jarOut.putNextEntry(JarEntry(entry.name))
                                input.copyTo(jarOut)
                                jarOut.closeEntry()
                            }
                        }
                    }
                }

                // Write merged services
                for ((serviceName, impls) in services) {
                    val fullPath = servicesPath.resolve(serviceName).toString()
                    jarOut.putNextEntry(JarEntry(fullPath))
                    jarOut.write(impls.joinToString("\n").toByteArray(Charsets.UTF_8))
                    jarOut.closeEntry()
                }
            }

            feedback?.invoke("✅ Packaged ${inputJars.size} jars into $output")
            output
        }.mapLeft { err ->
            val msg = "💥 Packaging failed: ${err.message}"
            feedback?.invoke(msg)
            ZpmError.PackagingFailed(msg)
        }
    }
}
