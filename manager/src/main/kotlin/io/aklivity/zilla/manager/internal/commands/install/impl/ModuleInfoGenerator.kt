package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import io.aklivity.zilla.manager.internal.utils.JarCopyMode
import io.aklivity.zilla.manager.internal.utils.JarEntryFilter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.jar.JarFile
import java.util.spi.ToolProvider
import kotlin.io.path.*
import java.nio.file.*
import java.util.jar.*
import java.util.zip.ZipEntry

open class ModuleInfoGenerator(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null,
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win"),
    private val retryDelay: Long = if (isWindows) 500L else 100L,
    private val maxRetries: Int = 10,
    private val cacheDir: Path? = null
) {

    open fun makeModuleInfoForAutomaticJar(jarPath: Path, outputDir: Path, module: ZpmModuleKt): Either<ZpmResolutionErrorKt, Path> {
        // Validate module
        if (module.name == null) {
            feedback?.invoke("⚠️ Skipping module-info generation for unnamed module ${module.id}")
            return ZpmResolutionErrorKt.DependencyResolutionError("Cannot generate module-info for unnamed module ${module.id}").left()
        }
        if (!module.automatic) {
            feedback?.invoke("⚠️ Skipping module-info generation for non-automatic module ${module.name}")
            return ZpmResolutionErrorKt.DependencyResolutionError("Module ${module.name} is not automatic").left()
        }
        if (module.paths.isEmpty()) {
            feedback?.invoke("⚠️ Skipping module-info generation for module ${module.name} with no paths")
            return ZpmResolutionErrorKt.DependencyResolutionError("No artifact paths for module ${module.name}").left()
        }

        // Validate jarPath
        if (!jarPath.exists()) {
            feedback?.invoke("⚠️ JAR $jarPath does not exist")
            return ZpmResolutionErrorKt.DependencyResolutionError("JAR does not exist: $jarPath").left()
        }
        if (!jarPath.isRegularFile()) {
            feedback?.invoke("⚠️ JAR path $jarPath is not a regular file")
            return ZpmResolutionErrorKt.DependencyResolutionError("Invalid JAR path: $jarPath is not a file").left()
        }
        if (!jarPath.isReadable()) {
            feedback?.invoke("⚠️ JAR $jarPath is unreadable")
            return ZpmResolutionErrorKt.DependencyResolutionError("Unreadable JAR: $jarPath").left()
        }

        // Validate JAR integrity
        try {
            JarFile(jarPath.toFile()).use { jarFile ->
                jarFile.entries().asSequence().take(1).forEach { entry ->
                    if (!entry.isDirectory) {
                        try {
                            jarFile.getInputStream(entry).use { it.read() }
                        } catch (e: Exception) {
                            feedback?.invoke("❌ Invalid JAR file $jarPath: ${e.message}")
                            throw IOException("Invalid JAR file $jarPath: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            feedback?.invoke("❌ Error validating JAR $jarPath: ${e.message}")
            return ZpmResolutionErrorKt.DependencyResolutionError("Failed to validate JAR $jarPath: ${e.message}").left()
        }

        val moduleInfoDir = outputDir.resolve(module.name!!).createDirectories()
        val moduleInfoPath = moduleInfoDir.resolve("module-info.java")
        feedback?.invoke("📄 Generating module-info.java for ${module.name} from $jarPath")

        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would generate module-info.java for: ${module.name}")
            return moduleInfoPath.right()
        }

        return ensureDirectoryWritable(moduleInfoDir, "module-info directory").flatMap {
            // Run jdeps to generate module-info.java
            val jdeps = ToolProvider.findFirst("jdeps").orElseThrow { IllegalStateException("jdeps not found") }
            val modulePath = buildString {
                append(outputDir.parent.resolve("modules"))
                if (cacheDir != null && cacheDir.exists()) {
                    append(File.pathSeparator)
                    append(cacheDir)
                }
            }
            val jdepsArgs = listOf(
                "--generate-open-module",
                moduleInfoDir.toString(),
                "--module-path",
                modulePath,
                jarPath.toString()
            )
            feedback?.invoke("📜 jdeps command for ${module.name}: ${jdepsArgs.joinToString(" ")}")
            val jdepsOut = ByteArrayOutputStream()
            val jdepsErr = ByteArrayOutputStream()
            val jdepsExitCode = jdeps.run(PrintStream(jdepsOut), PrintStream(jdepsErr), *jdepsArgs.toTypedArray())
            val jdepsOutStr = jdepsOut.toString(Charsets.UTF_8)
            val jdepsErrStr = jdepsErr.toString(Charsets.UTF_8)

            if (jdepsOutStr.isNotEmpty()) {
                feedback?.invoke("📜 jdeps output for ${module.name}: $jdepsOutStr")
            }
            if (jdepsErrStr.isNotEmpty()) {
                feedback?.invoke("📜 jdeps error output for ${module.name}: $jdepsErrStr")
            }

            if (jdepsExitCode != 0 || !moduleInfoPath.exists()) {
                feedback?.invoke("⚠️ jdeps failed or did not generate module-info.java for ${module.name}: $jdepsErrStr")
                // Fallback to basic module-info.java
                val fallbackContent = buildString {
                    appendLine("open module ${module.name} {")
                    module.depends.forEach { dep ->
                        appendLine("    requires $dep;")
                    }
                    appendLine("}")
                }
                try {
                    Files.writeString(moduleInfoPath, fallbackContent)
                    feedback?.invoke("✅ Generated fallback module-info.java for ${module.name}")
                    return@flatMap moduleInfoPath.right()
                } catch (e: Exception) {
                    feedback?.invoke("❌ Failed to generate fallback module-info.java for ${module.name}: ${e.message}")
                    return@flatMap ZpmResolutionErrorKt.DependencyResolutionError("Failed to generate fallback module-info.java: ${e.message}").left()
                }
            }

            // Filter module-info.java for valid packages
            val moduleInfoContent = Files.readString(moduleInfoPath)
            val validPackages = getValidPackages(jarPath)
            val filteredContent = filterModuleInfo(moduleInfoContent, validPackages, module)
            try {
                Files.writeString(moduleInfoPath, filteredContent)
                feedback?.invoke("✅ Generated and filtered $moduleInfoPath")
                moduleInfoPath.right()
            } catch (e: Exception) {
                feedback?.invoke("❌ Failed to filter module-info.java for ${module.name}: ${e.message}")
                ZpmResolutionErrorKt.DependencyResolutionError("Failed to filter module-info.java: ${e.message}").left()
            }
        }
    }

    open fun createDelegatedStubs(delegate: ZpmModuleKt, outputDir: Path): Either<ZpmResolutionErrorKt, Path> {
        if (delegate.name == null) {
            feedback?.invoke("⚠️ Skipping module-info generation for unnamed module ${delegate.id}")
            return ZpmResolutionErrorKt.DependencyResolutionError("Cannot generate module-info for unnamed module ${delegate.id}").left()
        }
        if (!delegate.automatic) {
            feedback?.invoke("⚠️ Skipping module-info generation for non-automatic module ${delegate.name}")
            return ZpmResolutionErrorKt.DependencyResolutionError("Module ${delegate.name} is not automatic").left()
        }

        val moduleInfoDir = outputDir.resolve(delegate.name!!).createDirectories()
        val moduleInfoPath = moduleInfoDir.resolve("module-info.java")
        feedback?.invoke("📄 Generating delegate module-info.java for ${delegate.name}")

        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would generate delegate module-info.java for: ${delegate.name}")
            try {
                Files.writeString(
                    moduleInfoPath,
                    """
                    open module ${delegate.name} {
                        requires transitive io.aklivity.zilla.manager.delegate;
                    }
                    """.trimIndent()
                )
                return moduleInfoPath.right()
            } catch (e: Exception) {
                return ZpmResolutionErrorKt.DependencyResolutionError("Failed to generate dry-run delegate module-info: ${e.message}").left()
            }
        }

        return ensureDirectoryWritable(moduleInfoDir, "delegate module-info directory").flatMap {
            try {
                val moduleInfoContent = """
                    open module ${delegate.name} {
                        requires transitive io.aklivity.zilla.manager.delegate;
                    }
                """.trimIndent()
                Files.writeString(moduleInfoPath, moduleInfoContent)
                feedback?.invoke("✅ Generated delegate $moduleInfoPath")
                moduleInfoPath.right()
            } catch (e: Exception) {
                feedback?.invoke("❌ Error generating delegate module-info: ${e.message}")
                ZpmResolutionErrorKt.DependencyResolutionError("Failed to generate delegate module-info: ${e.message}").left()
            }
        }
    }


    open fun buildDelegateModule(
        delegate: ZpmModuleKt,
        outputDir: Path,
        modulesDir: Path,
        ignoreMissingDependencies: Boolean = false
    ): Either<ZpmResolutionErrorKt, Path> {
        // Validating delegate module
        if (delegate.name == null) {
            feedback?.invoke("⚠️ Skipping delegate module generation for unnamed module ${delegate.id}")
            return ZpmResolutionErrorKt.DependencyResolutionError("Cannot generate delegate module for unnamed module ${delegate.id}").left()
        }
        if (delegate.paths.isEmpty()) {
            feedback?.invoke("⚠️ Delegate module ${delegate.name} has no paths, cannot build JAR")
            return ZpmResolutionErrorKt.DependencyResolutionError("Delegate module ${delegate.name} has no paths").left()
        }

        // Setting up directories and output path
        val generatedModulesDir = outputDir.resolve("modules").createDirectories()
        val generatedDelegateDir = generatedModulesDir.resolve(delegate.name!!).createDirectories()
        val generatedDelegatePath = generatedModulesDir.resolve("${delegate.name}.jar")
        val finalDelegatePath = modulesDir?.resolve("${delegate.name}.jar") ?: outputDir.resolve("${delegate.name}.jar")

        // Handling dry-run mode
        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would build delegate module ${delegate.name} from paths: ${delegate.paths}")
            try {
                val moduleInfoPath = generatedDelegateDir.resolve("module-info.java")
                Files.writeString(
                    moduleInfoPath,
                    """
                open module ${delegate.name} {
                   
                }
                """.trimIndent()
                )
                feedback?.invoke("🧪 [dry-run] Would create delegate JAR at $finalDelegatePath")
                return finalDelegatePath.right()
            } catch (e: Exception) {
                feedback?.invoke("❌ [dry-run] Failed to simulate delegate module generation: ${e.message}")
                return ZpmResolutionErrorKt.DependencyResolutionError("Failed to simulate delegate module generation: ${e.message}").left()
            }
        }

        // Ensuring directories are writable
        return ensureDirectoryWritable(generatedModulesDir, "generated modules directory").flatMap {
            ensureDirectoryWritable(generatedDelegateDir, "generated delegate directory").flatMap {
                Either.catch {
                    // Merging contents from delegate.paths into a single JAR
                    feedback?.invoke("📦 Merging ${delegate.paths.size} JARs for delegate module ${delegate.name}")
                    JarOutputStream(Files.newOutputStream(generatedDelegatePath)).use { moduleJar ->
                        val moduleInfoPath = "module-info.class"
                        val manifestPath = "META-INF/MANIFEST.MF"
                        val servicesPath = "META-INF/services"
                        val packageInfoName = "package-info.class"
                        val excludedPackage = "org/eclipse/yasson/internal/components"
                        val excludedClass = "BeanManagerInstanceCreator"
                        val entryNames = mutableSetOf<String>()
                        val services = mutableMapOf<String, MutableList<String>>()

                        // Processing each input JAR
                        delegate.paths.forEach { path ->
                            feedback?.invoke("📜 Processing input JAR: $path")
                            JarFile(path.toFile()).use { artifactJar ->
                                artifactJar.entries().asIterator().forEach { entry ->
                                    val entryName = entry.name
                                    if (!JarEntryFilter.shouldCopy(entryName, JarCopyMode.MERGE)) {
                                        feedback?.invoke("   🚫 Skipping excluded entry: $entryName")
                                        return@forEach
                                    }
                                    artifactJar.getInputStream(entry).use { input ->
                                        if (entryName.startsWith(servicesPath) && !entry.isDirectory) {
                                            // Collecting service providers for fusion
                                            val serviceName = entryName.removePrefix("$servicesPath/")
                                            val serviceImpl = input.readBytes().toString(Charsets.UTF_8)
                                            services.getOrPut(serviceName) { mutableListOf() }.addAll(serviceImpl.lines().filter { it.isNotBlank() })
                                            feedback?.invoke("   📋 Collecting service $serviceName from $entryName")
                                        } else if (entryNames.add(entryName)) {
                                            // Copying non-service, non-excluded entries
                                            val newEntry = JarEntry(entryName).apply { time = entry.time }
                                            moduleJar.putNextEntry(newEntry)
                                            if (!entry.isDirectory) {
                                                input.copyTo(moduleJar)
                                            }
                                            moduleJar.closeEntry()
                                            feedback?.invoke("   ✅ Copied entry: $entryName")
                                        }
                                    }
                                }
                            }
                        }

                        // Writing fused services
                        services.forEach { (serviceName, impls) ->
                            val servicePath = "$servicesPath/$serviceName"
                            val serviceImpl = impls.distinct().joinToString("\n")
                            val newEntry = JarEntry(servicePath).apply { time = 318240000000L }
                            moduleJar.putNextEntry(newEntry)
                            moduleJar.write(serviceImpl.toByteArray(Charsets.UTF_8))
                            moduleJar.closeEntry()
                            feedback?.invoke("✅ Fused service $serviceName with ${impls.distinct().size} implementations")
                        }

                        feedback?.invoke("✅ Merged delegate JAR contents at $generatedDelegatePath")
                    }

                    // Generating module-info.java using jdeps (always as open module)
                    feedback?.invoke("📝 Generating module-info.java for delegate module ${delegate.name} (open module)")
                    val jdeps = ToolProvider.findFirst("jdeps").orElseThrow { IllegalStateException("jdeps not found") }
                    val jdepsArgs = mutableListOf(
                        "--generate-open-module", generatedModulesDir.toString(),
                        "--module-path", modulesDir?.toString() ?: "",
                        generatedDelegatePath.toString()
                    )
                    if (ignoreMissingDependencies) {
                        jdepsArgs.add(0, "--ignore-missing-deps")
                    }

                    feedback?.invoke("📜 jdeps command: ${jdepsArgs.joinToString(" ")}")
                    val jdepsOut = ByteArrayOutputStream()
                    val jdepsErr = ByteArrayOutputStream()
                    val jdepsExitCode = jdeps.run(PrintStream(jdepsOut), PrintStream(jdepsErr), *jdepsArgs.toTypedArray())
                    val jdepsOutStr = jdepsOut.toString(Charsets.UTF_8)
                    val jdepsErrStr = jdepsErr.toString(Charsets.UTF_8)

                    if (jdepsOutStr.isNotEmpty()) feedback?.invoke("📜 jdeps output: $jdepsOutStr")
                    if (jdepsErrStr.isNotEmpty()) feedback?.invoke("📜 jdeps error: $jdepsErrStr")

                    val generatedModuleInfo = generatedDelegateDir.resolve("module-info.java")
                    if (jdepsExitCode != 0 || !generatedModuleInfo.exists()) {
                        feedback?.invoke("❌ jdeps failed to generate module-info.java: $jdepsErrStr")
                        throw IOException("Failed to generate module-info.java for delegate module: $jdepsErrStr")
                    }

                    // Compiling module-info.java
                    feedback?.invoke("📜 Compiling module-info.java for ${delegate.name}")
                    val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }
                    val javacArgs = mutableListOf<String>()
                    if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
                    javacArgs.addAll(listOf(
                        "--module-path", modulesDir?.toString() ?: "",
                        "-d", generatedDelegateDir.toString(),
                        generatedModuleInfo.toString()
                    ))

                    feedback?.invoke("📜 javac command: ${javacArgs.joinToString(" ")}")
                    val javacOut = ByteArrayOutputStream()
                    val javacErr = ByteArrayOutputStream()
                    val javacExitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
                    val javacOutStr = javacOut.toString(Charsets.UTF_8)
                    val javacErrStr = javacErr.toString(Charsets.UTF_8)

                    if (javacOutStr.isNotEmpty()) feedback?.invoke("📜 javac output: $javacOutStr")
                    if (javacErrStr.isNotEmpty()) feedback?.invoke("📜 javac error: $javacErrStr")

                    if (javacExitCode != 0) {
                        feedback?.invoke("❌ javac failed for delegate module: $javacErrStr")
                        throw IOException("javac failed for delegate module: $javacErrStr")
                    }

                    val compiledModuleInfo = generatedDelegateDir.resolve("module-info.class")
                    if (!compiledModuleInfo.exists()) {
                        feedback?.invoke("❌ Compiled module-info.class not found for ${delegate.name}")
                        throw IOException("Compiled module-info.class not found for ${delegate.name}")
                    }

                    // Adding module-info.class to the final JAR
                    feedback?.invoke("📦 Finalizing delegate JAR at $finalDelegatePath")
                    val tempJar = Files.createTempFile("temp-delegate", ".jar")
                    JarFile(generatedDelegatePath.toFile()).use { sourceJar ->
                        JarOutputStream(Files.newOutputStream(tempJar)).use { targetJar ->
                            sourceJar.entries().asIterator().forEach { entry ->
                                targetJar.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
                                if (!entry.isDirectory) {
                                    sourceJar.getInputStream(entry).copyTo(targetJar)
                                }
                                targetJar.closeEntry()
                            }

                            val moduleInfoEntry = JarEntry("module-info.class").apply { time = 318240000000L }
                            targetJar.putNextEntry(moduleInfoEntry)
                            Files.copy(compiledModuleInfo, targetJar)
                            targetJar.closeEntry()
                        }
                    }

                    Files.move(tempJar, finalDelegatePath, StandardCopyOption.REPLACE_EXISTING)
                    feedback?.invoke("✅ Built delegate module JAR at $finalDelegatePath (open module)")
                    finalDelegatePath
                }.mapLeft { ex ->
                    feedback?.invoke("❌ Failed to build delegate module ${delegate.name}: ${ex.message}")
                    ZpmResolutionErrorKt.DependencyResolutionError("Failed to build delegate module ${delegate.name}: ${ex.message}")
                }
            }
        }
    }

    private fun getValidPackages(jarPath: Path): Set<String> {
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                jar.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .map { it.name.substringBeforeLast('/').replace('/', '.') }
                    .filter { it.isNotBlank() }
                    .toSet()
            }
        } catch (e: Exception) {
            feedback?.invoke("❌ Failed to read packages from JAR $jarPath: ${e.message}")
            emptySet()
        }
    }

    private fun filterModuleInfo(content: String, validPackages: Set<String>, module: ZpmModuleKt): String {
        val lines = content.lines()
        val filteredLines = lines.filter { line ->
            if (line.trim().startsWith("exports")) {
                val packageName = line.trim().substringAfter("exports").substringBefore(';').trim()
                validPackages.contains(packageName)
            } else {
                true
            }
        }
        val moduleName = module.name!!
        val requiresLines = module.depends.map { "    requires $it;" }
        val filteredContent = if (filteredLines.size <= 2) {
            buildString {
                appendLine("open module $moduleName {")
                requiresLines.forEach { appendLine(it) }
                appendLine("}")
            }
        } else {
            buildString {
                appendLine("open module $moduleName {")
                requiresLines.forEach { appendLine(it) }
                filteredLines.filter { it.trim().startsWith("exports") }.forEach { appendLine(it) }
                appendLine("}")
            }
        }
        return filteredContent
    }

    private fun ensureDirectoryWritable(
        dir: Path,
        dirName: String
    ): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("🔍 Checking write permissions for $dirName: $dir")
        var retries = maxRetries
        var dirCreated = dir.exists() && dir.isWritable()

        while (retries > 0 && !dirCreated) {
            try {
                dir.createDirectories()
                if (!isWindows) {
                    Files.setPosixFilePermissions(
                        dir,
                        PosixFilePermissions.fromString("rwxrwxr-x")
                    )
                }
                dirCreated = dir.isWritable()
                if (!dirCreated) {
                    feedback?.invoke("⚠️ Directory $dir is not writable, retrying ($retries retries left)")
                    Thread.sleep(retryDelay)
                    retries--
                }
            } catch (e: FileSystemException) {
                feedback?.invoke("⚠️ Failed to create/write to $dir: ${e.message}, retrying ($retries retries left)")
                Thread.sleep(retryDelay)
                retries--
            }
        }

        if (!dirCreated) {
            feedback?.invoke("❌ Failed to ensure $dirName is writable after retries")
            throw _root_ide_package_.java.nio.file.FileSystemException("Failed to ensure $dirName is writable: $dir")
        }
        feedback?.invoke("✅ $dirName is writable: $dir")
    }.mapLeft {
        feedback?.invoke("❌ Failed to ensure $dirName is writable: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to ensure $dirName is writable: ${it.message}")
    }.map { Unit }

    private fun hasRealModuleInfo(jarPath: Path): Boolean {
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                jar.entries().asSequence().any { entry ->
                    val name = entry.name
                    !entry.isDirectory && (
                            name == "module-info.class" ||
                                    name.startsWith("META-INF/versions/") && name.endsWith("module-info.class")
                            )
                }
            }
        } catch (e: Exception) {
            feedback?.invoke("⚠️ Failed to inspect $jarPath for module-info: ${e.message}")
            false
        }
    }

    fun generateJavaInjectStub(target: Path) {
        val tmpDir = Files.createTempDirectory("java-inject-stub")
        val moduleInfo = tmpDir.resolve("module-info.java")

        // Write minimal module-info
        Files.write(
            moduleInfo, """
            module java.inject {
                exports javax.inject;
            }
        """.trimIndent().toByteArray()
        )

        // Create a dummy javax.inject.Inject annotation (package cannot be empty)
        val pkgDir = tmpDir.resolve("javax/inject")
        Files.createDirectories(pkgDir)
        Files.write(
            pkgDir.resolve("Inject.java"), """
            package javax.inject;
            public @interface Inject {}
        """.trimIndent().toByteArray()
        )

        // Compile both module-info.java and dummy annotation
        val javac = ProcessBuilder(
            "javac",
            moduleInfo.toAbsolutePath().toString(),
            pkgDir.resolve("Inject.java").toAbsolutePath().toString()
        ).directory(tmpDir.toFile())
            .inheritIO()
            .start()

        if (javac.waitFor() != 0) {
            throw RuntimeException("Failed to compile java.inject stub")
        }

        // Package into JAR with module-info.class
        val jar = ProcessBuilder(
            "jar", "--create", "--file", target.toAbsolutePath().toString(),
            "-C", tmpDir.toAbsolutePath().toString(), "."
        ).inheritIO().start()

        if (jar.waitFor() != 0) {
            throw RuntimeException("Failed to package java.inject stub")
        }
    }


    fun patchMultiReleaseJar(jarPath: Path, feedback: ((String) -> Unit)? = null): Path {
        val file = jarPath.toFile()
        if (!file.exists()) return jarPath

        var hasRootModuleInfo = false
        var bestVersionedEntry: JarEntry? = null
        var bestVersion: Int = -1
        var moduleInfoBytes: ByteArray? = null

        // 🔍 Scan for root + versioned module-info.class
        JarFile(file).use { jar ->
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name

                if (name == "module-info.class") {
                    hasRootModuleInfo = true
                }

                if (name.startsWith("META-INF/versions/") &&
                    name.substringAfterLast("/") == "module-info.class"
                ) {
                    val versionPart = name.removePrefix("META-INF/versions/").substringBefore('/')
                    val version = versionPart.toIntOrNull() ?: -1
                    if (version > bestVersion) {
                        bestVersion = version
                        bestVersionedEntry = entry
                    }
                }
            }

            if (bestVersionedEntry != null && !hasRootModuleInfo) {
                moduleInfoBytes = jar.getInputStream(bestVersionedEntry).use { it.readBytes() }
            }
        }

        // ✅ No patch needed
        if (hasRootModuleInfo || bestVersionedEntry == null || moduleInfoBytes == null) {
            feedback?.invoke("ℹ️ No patch needed for ${jarPath.fileName} (root=$hasRootModuleInfo, bestVersion=$bestVersion)")
            return jarPath
        }

        // ✍️ Rewrite JAR
        val tmpFile = File.createTempFile("patched-", ".jar")
        JarFile(file).use { jar ->
            JarOutputStream(FileOutputStream(tmpFile)).use { jos ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    // Skip root-level module-info (we’re replacing it)
                    if (name == "module-info.class") continue

                    // 🚫 Skip *all* META-INF/versions entries except the one we promoted
                    if (name.startsWith("META-INF/versions/")) continue

                    // Copy everything else verbatim
                    jos.putNextEntry(ZipEntry(name))
                    jar.getInputStream(entry).use { it.copyTo(jos) }
                    jos.closeEntry()
                }

                // Add promoted module-info.class at root
                val newEntry = ZipEntry("module-info.class")
                jos.putNextEntry(newEntry)
                jos.write(moduleInfoBytes)
                jos.closeEntry()
            }
        }

        feedback?.invoke("📦 Patched ${jarPath.fileName}: promoted module-info.class from META-INF/versions/$bestVersion to root and stripped multi-release entries")

        Files.move(tmpFile.toPath(), jarPath, StandardCopyOption.REPLACE_EXISTING)
        return jarPath
    }

    private fun atLeastVersion(tool: ToolProvider, major: Int): Boolean {
        val out = ByteArrayOutputStream()
        tool.run(PrintStream(out), System.err, "--version")
        val matcher = Regex("""(\d+)\.""").find(out.toString(Charsets.UTF_8))
        val version = matcher?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        val atLeast = version >= major
        return atLeast
    }

}