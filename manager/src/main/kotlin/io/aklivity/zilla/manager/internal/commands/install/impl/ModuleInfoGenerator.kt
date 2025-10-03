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
import java.lang.module.ModuleFinder
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

    open fun createDelegatedStubs(
        delegate: ZpmModuleKt,
        outputDir: Path
    ): Either<ZpmResolutionErrorKt, Path> {
        if (delegate.name == null) {
            feedback?.invoke("⚠️ Skipping module-info generation for unnamed module ${delegate.id}")
            return ZpmResolutionErrorKt.DependencyResolutionError(
                "Cannot generate module-info for unnamed module ${delegate.id}"
            ).left()
        }
        if (!delegate.automatic) {
            feedback?.invoke("⚠️ Skipping module-info generation for non-automatic module ${delegate.name}")
            return ZpmResolutionErrorKt.DependencyResolutionError(
                "Module ${delegate.name} is not automatic"
            ).left()
        }

        // Each stub gets its own folder
        val moduleDir = outputDir.resolve(delegate.name!!).createDirectories()
        val moduleInfoPath = moduleDir.resolve("module-info.java")

        feedback?.invoke("📄 Generating stub module-info.java for ${delegate.name}")

        val content = """
        open module ${delegate.name} {
            requires transitive io.aklivity.zilla.manager.delegate;
        }
    """.trimIndent()

        return if (dryRun) {
            Either.catch {
                feedback?.invoke("🧪 [dry-run] Would generate stub for ${delegate.name}")
                Files.writeString(moduleInfoPath, content)
                moduleInfoPath
            }.mapLeft { ex ->
                ZpmResolutionErrorKt.DependencyResolutionError(
                    "Failed to simulate stub generation for ${delegate.name}: ${ex.message}"
                )
            }
        } else {
            ensureDirectoryWritable(moduleDir, "delegate module-info directory").flatMap {
                Either.catch {
                    Files.writeString(moduleInfoPath, content)
                    feedback?.invoke("✅ Generated stub module-info.java for ${delegate.name} at $moduleInfoPath")
                    moduleInfoPath
                }.mapLeft { ex ->
                    feedback?.invoke("❌ Error generating stub for ${delegate.name}: ${ex.message}")
                    ZpmResolutionErrorKt.DependencyResolutionError(
                        "Failed to generate stub for ${delegate.name}: ${ex.message}"
                    )
                }
            }
        }
    }

    private fun getJarDependencies(jarPath: Path): Set<String> {
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                val moduleFinder = ModuleFinder.of(jarPath)
                val moduleRefs = moduleFinder.findAll()
                if (moduleRefs.isNotEmpty()) {
                    // Named module: extract requires from module descriptor
                    moduleRefs.flatMap { ref ->
                        ref.descriptor().requires().map { it.name() }
                    }.toSet()
                } else {
                    // Automatic module: check for Automatic-Module-Name in manifest
                    val manifest = jar.manifest
                    val moduleName = manifest?.mainAttributes?.getValue("Automatic-Module-Name")
                    if (moduleName != null) {
                        setOf(moduleName) // Treat it as a named module with no requires
                    } else {
                        emptySet() // Unnamed module, no dependencies
                    }
                }
            }
        } catch (e: Exception) {
            feedback?.invoke("⚠️ Failed to read dependencies from JAR $jarPath: ${e.message}")
            emptySet()
        }
    }

    open fun buildDelegateModule(
        delegate: ZpmModuleKt,
        outputDir: Path,
        modulesDir: Path,
        ignoreMissingDependencies: Boolean = true
    ): Either<ZpmResolutionErrorKt, Path> {
        if (delegate.name == null) {
            feedback?.invoke("⚠️ Skipping delegate module generation for unnamed module ${delegate.id}")
            return ZpmResolutionErrorKt.DependencyResolutionError(
                "Cannot generate delegate module for unnamed module ${delegate.id}"
            ).left()
        }
        if (delegate.paths.isEmpty()) {
            feedback?.invoke("⚠️ Delegate module ${delegate.name} has no paths, cannot build JAR")
            return ZpmResolutionErrorKt.DependencyResolutionError(
                "Delegate module ${delegate.name} has no paths"
            ).left()
        }

        val cleanedPaths = delegate.paths.filterNot { path ->
            val fileName = path.fileName.toString()
            fileName.startsWith("kotlin") || fileName.startsWith("kotlin-stdlib-jdk8")
        }.toMutableSet()
        val effectiveDelegate = delegate.copy(paths = cleanedPaths)

        val generatedModulesDir = outputDir.resolve("modules").createDirectories()
        val generatedDelegateDir = generatedModulesDir.resolve(effectiveDelegate.name!!).createDirectories()
        val generatedDelegatePath = generatedModulesDir.resolve("${effectiveDelegate.name}.jar")
        val finalDelegatePath = modulesDir.resolve("${effectiveDelegate.name}.jar")

        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would build delegate module ${effectiveDelegate.name}")
            val moduleInfoPath = generatedDelegateDir.resolve("module-info.java")
            Files.writeString(
                moduleInfoPath,
                """
            open module ${effectiveDelegate.name} {
                // dry-run: no exports
            }
            """.trimIndent()
            )
            return finalDelegatePath.right()
        }

        return ensureDirectoryWritable(generatedModulesDir, "generated modules directory").flatMap {
            ensureDirectoryWritable(generatedDelegateDir, "generated delegate directory").flatMap {
                Either.catch {
                    // Collect all module names and dependencies from merged JARs
                    val mergedModuleNames = mutableSetOf<String>()
                    val allDependencies = mutableSetOf<String>()

                    // Merge JARs and collect dependencies
                    feedback?.invoke("📦 Merging ${effectiveDelegate.paths.size} JARs for delegate module ${effectiveDelegate.name}")
                    JarOutputStream(Files.newOutputStream(generatedDelegatePath)).use { moduleJar ->
                        val entryNames = mutableSetOf<String>()
                        effectiveDelegate.paths.forEach { path ->
                            val jarDependencies = getJarDependencies(path)
                            allDependencies.addAll(jarDependencies)
                            JarFile(path.toFile()).use { jar ->
                                val manifest = jar.manifest
                                val moduleName = manifest?.mainAttributes?.getValue("Automatic-Module-Name")
                                if (moduleName != null) {
                                    mergedModuleNames.add(moduleName)
                                } else if (hasRealModuleInfo(path)) {
                                    val moduleFinder = ModuleFinder.of(path)
                                    moduleFinder.findAll().forEach { ref ->
                                        mergedModuleNames.add(ref.descriptor().name())
                                    }
                                }
                                jar.entries().asIterator().forEach { entry ->
                                    if (entryNames.add(entry.name)) {
                                        moduleJar.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
                                        if (!entry.isDirectory) {
                                            jar.getInputStream(entry).copyTo(moduleJar)
                                        }
                                        moduleJar.closeEntry()
                                    }
                                }
                            }
                        }
                    }

                    // Filter out dependencies that are merged into the delegate
                    val externalDependencies = (allDependencies - mergedModuleNames).toMutableSet()
                    externalDependencies.add("jdk.unsupported") // Ensure jdk.unsupported is included
                    externalDependencies.add("kotlin.stdlib") // Ensure kotlin.stdlib is required

                    // Run jdeps
                    feedback?.invoke("📝 Running jdeps for ${effectiveDelegate.name} (ignore-missing-deps enabled)")
                    val jdeps = ToolProvider.findFirst("jdeps")
                        .orElseThrow { IllegalStateException("jdeps not found") }
                    val jdepsArgs = mutableListOf(
                        "--ignore-missing-deps",
                        "--generate-open-module", generatedDelegateDir.toString(),
                        "--module-path", modulesDir.toString(),
                        generatedDelegatePath.toString()
                    )

                    val jdepsOut = ByteArrayOutputStream()
                    val jdepsErr = ByteArrayOutputStream()
                    val jdepsExitCode = jdeps.run(PrintStream(jdepsOut), PrintStream(jdepsErr), *jdepsArgs.toTypedArray())
                    val jdepsErrStr = jdepsErr.toString(Charsets.UTF_8)

                    val generatedModuleInfo = generatedDelegateDir.resolve("module-info.java")
                    if (jdepsExitCode != 0 || !generatedModuleInfo.exists()) {
                        feedback?.invoke("⚠️ jdeps failed, falling back to minimal module-info: $jdepsErrStr")
                    }

                    // Patch module-info with exports and filtered requires
                    val allPackages = getValidPackages(generatedDelegatePath)
                    val patchedContent = buildString {
                        appendLine("open module ${effectiveDelegate.name} {")
                        allPackages.forEach { pkg -> appendLine("    exports $pkg;") }
                        externalDependencies.forEach { dep -> appendLine("    requires $dep;") }
                        appendLine("}")
                    }
                    Files.writeString(generatedModuleInfo, patchedContent)

                    // Compile module-info.java
                    feedback?.invoke("📝 Compiling module-info.java with module path: ${modulesDir}")
                    val javac = ToolProvider.findFirst("javac")
                        .orElseThrow { IllegalStateException("javac not found") }

                    val javacArgs = listOf(
                        "-proc:none",
                        "--module-path", modulesDir.toString(),
                        "--patch-module", "${effectiveDelegate.name}=${generatedDelegatePath.toString()}",
                        "-d", generatedDelegateDir.toString(),
                        generatedModuleInfo.toString()
                    )

                    val javacOut = ByteArrayOutputStream()
                    val javacErr = ByteArrayOutputStream()
                    val javacExitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
                    val javacErrStr = javacErr.toString(Charsets.UTF_8)
                    if (javacExitCode != 0) {
                        feedback?.invoke("❌ javac error: $javacErrStr")
                        throw IOException("javac failed: $javacErrStr")
                    }

                    // Finalize JAR
                    val compiledModuleInfo = generatedDelegateDir.resolve("module-info.class")
                    val tempJar = Files.createTempFile("temp-delegate", ".jar")
                    JarFile(generatedDelegatePath.toFile()).use { src ->
                        JarOutputStream(Files.newOutputStream(tempJar)).use { dst ->
                            src.entries().asIterator().forEach { entry ->
                                dst.putNextEntry(JarEntry(entry.name).apply { time = entry.time })
                                if (!entry.isDirectory) src.getInputStream(entry).copyTo(dst)
                                dst.closeEntry()
                            }
                            val moduleInfoEntry = JarEntry("module-info.class").apply { time = 318240000000L }
                            dst.putNextEntry(moduleInfoEntry)
                            Files.copy(compiledModuleInfo, dst)
                            dst.closeEntry()
                        }
                    }
                    Files.move(tempJar, finalDelegatePath, StandardCopyOption.REPLACE_EXISTING)

                    feedback?.invoke("✅ Built delegate JAR at $finalDelegatePath with ${allPackages.size} exports and ${externalDependencies.size} requires")
                    finalDelegatePath
                }.mapLeft { ex ->
                    feedback?.invoke("❌ Failed to build delegate module ${delegate.name}: ${ex.message}")
                    ZpmResolutionErrorKt.DependencyResolutionError(
                        "Failed to build delegate module ${delegate.name}: ${ex.message}"
                    )
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
                    .filter { pkg ->
                        // ignore default (root) package and META-INF
                        pkg.isNotBlank() &&
                                !pkg.startsWith("META-INF") &&
                                !pkg.startsWith("module-info") &&
                                !pkg.startsWith("package-info")
                    }
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