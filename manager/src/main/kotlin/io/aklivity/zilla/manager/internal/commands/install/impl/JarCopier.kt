package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import java.io.*
import java.nio.file.*
import java.util.jar.*
import java.util.zip.ZipFile

open class JarCopier(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) {
    private val manifestPath = Paths.get("META-INF", "MANIFEST.MF")
    private val servicesPath = Paths.get("META-INF", "services")
    private val excludedPackage = Paths.get("org", "eclipse", "yasson", "internal", "components")
    private val excludedClass = "BeanManagerInstanceCreator"
    private val excludedFiles = setOf("module-info.class", "package-info.class")

    open fun copyJars(inputJars: List<Path>, outputJar: Path): Either<ZpmError, Path> = Either.catch {
        Files.createDirectories(outputJar.parent)

        val entryNames = mutableSetOf<String>()
        val services = mutableMapOf<String, MutableList<String>>()

        if (dryRun) feedback?.invoke("🧪 [dry-run] Would copy into: $outputJar")
        else feedback?.invoke("🛠 Writing JAR to: $outputJar")

        if (!dryRun) {
            JarOutputStream(Files.newOutputStream(outputJar)).use { out ->
                for (jar in inputJars) {
                    JarFile(jar.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { jarFile ->
                        for (entry in jarFile.entries()) {
                            val entryName = entry.name
                            val entryPath = Paths.get(entryName)

                            if (shouldExclude(entryPath)) {
                                feedback?.invoke("⚠️ Excluded: $entryName")
                                continue
                            }

                            val inputStream = jarFile.getInputStream(entry)
                            if (entryPath.startsWith(servicesPath) && entryPath.nameCount == servicesPath.nameCount + 1) {
                                val serviceName = servicesPath.relativize(entryPath).toString()
                                val impls = inputStream.bufferedReader().readLines()
                                services.computeIfAbsent(serviceName) { mutableListOf() }.addAll(impls)
                            } else if (entryNames.add(entryName)) {
                                val bytes = inputStream.readAllBytes()
                                val newEntry = JarEntry(entryName).apply { time = fixedTime }
                                out.putNextEntry(newEntry)
                                out.write(bytes)
                                out.closeEntry()
                                feedback?.invoke("✅ Copied: $entryName")
                            }
                        }
                    }
                }

                // Add merged services
                for ((serviceName, impls) in services) {
                    val servicePath = servicesPath.resolve(serviceName).toString()
                    val newEntry = JarEntry(servicePath).apply { time = fixedTime }
                    out.putNextEntry(newEntry)
                    out.write(impls.joinToString("\n").toByteArray(Charsets.UTF_8))
                    out.closeEntry()
                    feedback?.invoke("🔧 Merged service: $serviceName with ${impls.size} impl(s)")
                }
            }
        }

        outputJar
    }.mapLeft {
        feedback?.invoke("💥 Error during JAR copy: ${it.message}")
        ZpmError.PackagingFailed("Failed to copy jars into $outputJar: ${it.message}")
    }

    private fun shouldExclude(entry: Path): Boolean {
        return entry.toString() in excludedFiles ||
               entry == manifestPath ||
               entry.fileName.toString() in excludedFiles ||
               (entry.startsWith(excludedPackage) && entry.fileName.toString().startsWith(excludedClass))
    }

    companion object {
        private const val fixedTime = 318240000000L // constant for reproducible builds
    }
}
