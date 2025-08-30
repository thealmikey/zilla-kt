package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.ModulePackager
import io.aklivity.zilla.manager.internal.commands.install.ZpmError
import io.aklivity.zilla.manager.internal.utils.JarType
import io.aklivity.zilla.manager.internal.utils.classifyJar
import java.io.*
import java.nio.file.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class DefaultModulePackager(
    private val dryRun: Boolean = false,
    private val feedback: (String) -> Unit = {} // Non-nullable, default no-op
) : ModulePackager {

    override fun packageModule(inputJars: List<Path>, output: Path): Either<ZpmError, Path> {
        feedback("➡️ Starting packaging to ${output.toString().replace('\\', '/')}")

        // Validate inputs
        if (inputJars.isEmpty()) {
            feedback("❌ No input JARs provided")
            return ZpmError.PackagingFailed("No input JARs provided").left()
        }

        // Check JAR validity and classify
        val jarTypes = inputJars.map { jar ->
            when {
                !Files.exists(jar) -> {
                    val jarPath = jar.toString().replace('\\', '/')
                    feedback("⚠️ JAR does not exist: $jarPath")
                    feedback("❌ JAR does not exist: $jarPath")
                    return ZpmError.PackagingFailed("JAR does not exist: $jarPath").left()
                }
                !Files.isReadable(jar) -> {
                    val jarPath = jar.toString().replace('\\', '/')
                    feedback("⚠️ JAR not readable: $jarPath")
                    feedback("❌ JAR not readable: $jarPath")
                    return ZpmError.PackagingFailed("JAR not readable: $jarPath").left()
                }
                else -> jar to classifyJar(jar)
            }
        }

        // Handle classification results
        val validJars = jarTypes.mapNotNull { (jar, typeEither) ->
            typeEither.fold(
                { err ->
                    val jarPath = jar.toString().replace('\\', '/')
                    feedback("⚠️ Skipping $jarPath: ${err.message}")
                    null
                },
                { type ->
                    val jarPath = jar.toString().replace('\\', '/')
                    when (type) {
                        JarType.EMPTY -> {
                            feedback("⚠️ Skipping empty JAR: $jarPath")
                            null
                        }
                        JarType.RESOURCE_ONLY -> {
                            feedback("⚠️ Processing resource-only JAR: $jarPath")
                            jar
                        }
                        JarType.VALID -> {
                            feedback("⚠️ Processing valid JAR: $jarPath")
                            jar
                        }
                    }
                }
            )
        }

        if (validJars.isEmpty()) {
            feedback("❌ No valid or resource-only JARs to package")
            return ZpmError.PackagingFailed("No valid or resource-only JARs to package").left()
        }

        if (dryRun) {
            feedback("✅ [dry-run] Would package ${validJars.size} jars into ${output.toString().replace('\\', '/')}")
            return output.right()
        }

        return Either.catch {
            val excludedManifest = Paths.get("META-INF", "MANIFEST.MF")
            val excludedPackage = Paths.get("org", "eclipse", "yasson", "internal", "components")
            val excludedClass = "BeanManagerInstanceCreator"
            val excludedSuffixes = setOf("module-info.class", "package-info.class")
            val servicesPath = Paths.get("META-INF", "services")
            val services = mutableMapOf<String, MutableSet<String>>() // Use Set for deduplication
            val written = mutableSetOf<String>()

            try {
                Files.createDirectories(output.parent)
            } catch (e: IOException) {
                feedback("❌ Failed to create output directory ${output.parent.toString().replace('\\', '/')}: ${e.message}")
                throw e
            }

            JarOutputStream(Files.newOutputStream(output)).use { jarOut ->
                for (jar in validJars) {
                    try {
                        JarFile(jar.toFile()).use { jarFile ->
                            val entries = jarFile.entries()
                            while (entries.hasMoreElements()) {
                                val entry = entries.nextElement()
                                val entryPath = Paths.get(entry.name.replace('\\', '/'))

                                val shouldSkip =
                                    entryPath.toString() in excludedSuffixes ||
                                            entryPath == excludedManifest ||
                                            (entryPath.startsWith(excludedPackage) &&
                                                    entryPath.fileName.toString().startsWith(excludedClass))

                                if (shouldSkip) {
                                    feedback("⏩ Skipped ${entryPath.toString().replace('\\', '/')} from ${jar.toString().replace('\\', '/')}")
                                    continue
                                }

                                val input = jarFile.getInputStream(entry)
                                val normalizedEntryName = entry.name.replace('\\', '/')
                                if (entryPath.startsWith(servicesPath) &&
                                    entryPath.nameCount - servicesPath.nameCount == 1
                                ) {
                                    val serviceName = entryPath.fileName.toString()
                                    val serviceImpls = input.bufferedReader().readLines().filter { it.isNotBlank() }
                                    services.computeIfAbsent(serviceName) { mutableSetOf() }
                                        .addAll(serviceImpls)
                                } else if (written.add(normalizedEntryName)) {
                                    jarOut.putNextEntry(JarEntry(normalizedEntryName))
                                    input.use { it.copyTo(jarOut) }
                                    jarOut.closeEntry()
                                } else {
                                    feedback("⚠️ Skipped duplicate entry ${normalizedEntryName} from ${jar.toString().replace('\\', '/')}")
                                }
                            }
                        }
                    } catch (e: IOException) {
                        feedback("⚠️ Failed to process JAR ${jar.toString().replace('\\', '/')}: ${e.message}")
                        throw e
                    }
                }

                // Write merged services (deduplicated)
                for ((serviceName, impls) in services) {
                    val fullPath = servicesPath.resolve(serviceName).toString().replace('\\', '/')
                    jarOut.putNextEntry(JarEntry(fullPath))
                    jarOut.write(impls.joinToString("\n").toByteArray(Charsets.UTF_8))
                    jarOut.closeEntry()
                    feedback("✅ Merged service $serviceName with ${impls.size} implementations")
                }
            }

            feedback("✅ Packaged ${validJars.size} jars into ${output.toString().replace('\\', '/')}")
            output
        }.mapLeft { err ->
            val msg = "💥 Packaging failed: ${err.message ?: "Unknown error"}"
            feedback(msg)
            when (err) {
                is IOException -> ZpmError.PackagingFailed("IO error during packaging: ${err.message}")
                else -> ZpmError.PackagingFailed(msg)
            }
        }
    }
}