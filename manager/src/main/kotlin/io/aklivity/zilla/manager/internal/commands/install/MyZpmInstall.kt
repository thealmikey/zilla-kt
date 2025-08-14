package io.aklivity.zilla.manager.internal.commands.install

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.None
import arrow.core.Some
import arrow.core.flatMap
import arrow.core.flatten
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import io.aklivity.zilla.manager.internal.commands.install.cache.*
import io.aklivity.zilla.manager.internal.commands.install.impl.*
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.PrintStream
import java.lang.module.ModuleDescriptor
import java.lang.module.ModuleFinder
import java.nio.file.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import java.util.spi.ToolProvider
import kotlin.io.path.*

open class MyZpmInstall(
    private val cache: ZpmCacheKt,
    private val installDir: Path,
    private val jarCopier: JarCopier,
    private val manifestMerger: ManifestMerger,
    private val moduleInfoGenerator: ModuleInfoGenerator,
    private val imageLinker: DefaultImageLinker,
    private val launcherWriter: DefaultLauncherWriter,
    private val dryRun: Boolean = false,
    private val verbose: Boolean = false,
    private val ignoreMissingDependencies: Boolean = true,
    val feedback: ((String) -> Unit)? = null
) {
    private val logger = LoggerFactory.getLogger(MyZpmInstall::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val modulesDir: Path = installDir.resolve("modules")

    open fun ZpmError.toResolutionError(): ZpmResolutionErrorKt =
        ZpmResolutionErrorKt.DependencyResolutionError(this.toString())

    open fun Path.nameWithoutExtension(): String =
        fileName.toString().substringBeforeLast(".")

    open fun moduleDescriptor(jarPath: Path): ModuleDescriptor? = Either.catch {
        ModuleFinder.of(jarPath).findAll().firstOrNull()?.descriptor()
    }.getOrElse {
        feedback?.invoke("⚠️ Failed to load module descriptor for $jarPath: ${it.message}")
        null
    }

    fun installFromTemplate(templatePath: Path, feedback: ((String) -> Unit)? = null): Either<ZpmResolutionErrorKt, Path> {
        return checkTemplate(templatePath, feedback)
            .flatMap { parseTemplate(templatePath, feedback) }
            .flatMap { template -> resolveDependencies(template, feedback) }
            .flatMap { artifacts ->
                discoverAndMigrateModules(artifacts, feedback).flatMap { delegate ->
                    generateSystemOnlyAutomatic(artifacts.map { ZpmModuleKt(it.id.toString(), it.id, mutableSetOf(it.path), it.dependencies.map { dep -> ZpmDependencyKt(dep.groupId, dep.artifactId, if (dep.version.isNotBlank()) Some(
                        dep.version
                    ) else None) }.toMutableSet(), false, true) }, feedback)
                        .flatMap { processModules(delegate, artifacts.map { ZpmModuleKt(it.id.toString(), it.id, mutableSetOf(it.path), it.dependencies.map { dep -> ZpmDependencyKt(dep.groupId, dep.artifactId, if (dep.version.isNotBlank()) Some(dep.version) else None) }.toMutableSet(), false, true) }, feedback) }
                        .flatMap { targetJar -> generateDelegating(artifacts.map { ZpmModuleKt(it.id.toString(), it.id, mutableSetOf(it.path), it.dependencies.map { dep -> ZpmDependencyKt(dep.groupId, dep.artifactId, if (dep.version.isNotBlank()) Some(dep.version) else None) }.toMutableSet(), true, true) }, feedback).map { targetJar } }
                }
            }
            .flatMap { targetJar -> generateAndCompileModuleInfo(targetJar, feedback) }
            .flatMap { targetJar -> linkImageAndWriteLauncher(targetJar, feedback) }
    }

    open fun generateAndCompileModuleInfo(targetJar: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Path> {
        feedback?.invoke("📝 Merging manifests")
        val manifestPath = installDir.resolve("META-INF/MANIFEST.MF")
        return manifestMerger.merge(mutableListOf(), manifestPath).mapLeft {
            feedback?.invoke("❌ Failed to merge manifests: $it")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to merge manifests: $it")
        }.flatMap {
            feedback?.invoke("✅ Merged manifests to $manifestPath")
            feedback?.invoke("📄 Generating module-info.java")
            moduleInfoGenerator.generate(targetJar, installDir, ZpmModuleKt(null, null, mutableSetOf())).mapLeft {
                feedback?.invoke("❌ Failed to generate module-info: ${it.toString()}")
                ZpmResolutionErrorKt.DependencyResolutionError("Failed to generate module-info: ${it.toString()}")
            }
        }.flatMap { moduleInfoPath ->
            feedback?.invoke("✅ Generated $moduleInfoPath")
            feedback?.invoke("🛠 Compiling module-info.java")
            compileModuleInfo(moduleInfoPath).mapLeft {
                feedback?.invoke("❌ Failed to compile module-info: ${it.toString()}")
                it
            }.flatMap { moduleInfoClass ->
                feedback?.invoke("✅ Compiled $moduleInfoClass")
                feedback?.invoke("📦 Extending $targetJar with module-info.class")
                extendJar(targetJar, targetJar, JarEntry("module-info.class"), moduleInfoClass).mapLeft {
                    feedback?.invoke("❌ Failed to extend JAR: ${it.toString()}")
                    it
                }.map {
                    feedback?.invoke("✅ Extended $targetJar with module-info.class")
                    targetJar
                }
            }
        }
    }

    open fun parseTemplate(templatePath: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, ZpmTemplate> {
        feedback?.invoke("📜 Parsing template: $templatePath")
        return Either.catch {
            json.decodeFromString<ZpmTemplate>(Files.readString(templatePath))
        }.mapLeft {
            feedback?.invoke("❌ Failed to parse template: ${it.message}")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to parse template: ${it.message}")
        }
    }

    open fun resolveDependencies(template: ZpmTemplate, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> {
        feedback?.invoke("🔍 Converting ${template.imports.size} imports and ${template.dependencies.size} dependencies")
        val imports = template.imports.mapNotNull { ZpmDependencyKt.fromCoordinates(it) }
        val deps = template.dependencies.mapNotNull { ZpmDependencyKt.fromCoordinates(it) }
        feedback?.invoke("✅ Resolved ${imports.size} imports and ${deps.size} dependencies")

        feedback?.invoke("📦 Resolving dependencies via cache")
        return cache.resolveImports(imports, deps).mapLeft {
            feedback?.invoke("❌ Dependency resolution failed: ${it.message}")
            it
        }
    }

    open fun discoverAndMigrateModules(artifacts: List<ZpmArtifactKt>, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, ZpmModuleKt> {
        val delegate = ZpmModuleKt()
        val modules = discoverModules(artifacts, feedback).toMutableList()
        feedback?.invoke("MIKE discovered ${modules.size} modules")

        return migrateUnnamed(modules, delegate).mapLeft { errors: NonEmptyList<String> ->
            feedback?.invoke("❌ Migration errors: ${errors.joinToString(", ")}")
            ZpmResolutionErrorKt.DependencyResolutionError(errors.joinToString(", "))
        }.flatMap { migrated ->
            feedback?.invoke("MIKE migrated ${migrated.size} unnamed modules to delegate")
            delegateAutomatic(modules, delegate).mapLeft { errors: NonEmptyList<String> ->
                feedback?.invoke("❌ Delegation errors: ${errors.joinToString(", ")}")
                ZpmResolutionErrorKt.DependencyResolutionError(errors.joinToString(", "))
            }.map { delegate }
        }
    }

    open fun processModules(
        delegate: ZpmModuleKt,
        modules: Collection<ZpmModuleKt>,
        feedback: ((String) -> Unit)?
    ): Either<ZpmResolutionErrorKt, Path> {
        feedback?.invoke("📦 Processing modules")
        // Ensure modulesDir exists
        Files.createDirectories(modulesDir)
        feedback?.invoke("✅ Created modules directory: $modulesDir")

        val nonDelegating = modules.filterNot { it.delegating }
        nonDelegating.forEach { module ->
            val artifactPath = module.paths.first()
            if (!Files.exists(artifactPath) || !Files.isReadable(artifactPath)) {
                feedback?.invoke("❌ Invalid artifact path for ${module.name}: $artifactPath")
                return ZpmResolutionErrorKt.DependencyResolutionError("Invalid artifact path for ${module.name}: $artifactPath").left()
            }
            val modulePath = modulesDir.resolve("${module.name}.jar")
            Files.copy(artifactPath, modulePath, StandardCopyOption.REPLACE_EXISTING)
            feedback?.invoke("✅ Copied non-delegating module: ${module.name}")
        }

        if (delegate.paths.isEmpty()) {
            feedback?.invoke("⚠️ No delegate paths to process")
            return modulesDir.right()
        }

        // Validate delegate paths
        val invalidPaths = delegate.paths.filter { !Files.exists(it) || !Files.isReadable(it) }
        if (invalidPaths.isNotEmpty()) {
            feedback?.invoke("❌ Invalid delegate paths: ${invalidPaths.joinToString()}")
            return ZpmResolutionErrorKt.DependencyResolutionError("Invalid delegate paths: ${invalidPaths.joinToString()}").left()
        }

        val targetJar = modulesDir.resolve("zilla.delegate.jar")
        feedback?.invoke("📦 Generating delegate JAR: $targetJar")
        return jarCopier.copyJars(delegate.paths.toList(), targetJar)
            .flatMap {
                println("Mike, about to generate delegate")
                generateDelegate(delegate, targetJar, feedback).flatMap {
                    println("Mike, we generated the delegate")
                    feedback?.invoke("✅ Generated delegate JAR: $targetJar")
                    Either.Right(targetJar)
                }
            }
            .mapLeft {
                feedback?.invoke("❌ Failed to copy delegate JARs: $it")
                ZpmResolutionErrorKt.DependencyResolutionError("Failed to copy delegate JARs: $it")
            }
    }

    open fun generateDelegate(
        delegate: ZpmModuleKt,
        targetJar: Path,
        feedback: ((String) -> Unit)?
    ): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("📝 Generating delegate module: ${delegate.name ?: "zilla.delegate"}")
        if (delegate.paths.isEmpty()) {
            feedback?.invoke("❌ No paths to process in delegate module")
            throw IOException("No paths to process in delegate module")
        }
        feedback?.invoke("📂 Processing ${delegate.paths.size} JARs: ${delegate.paths.joinToString()}")

        // Validate delegate.paths
        val invalidPaths = delegate.paths.filter { !Files.exists(it) || !Files.isReadable(it) }
        if (invalidPaths.isNotEmpty()) {
            feedback?.invoke("❌ Invalid delegate paths: ${invalidPaths.joinToString()}")
            throw IOException("Invalid delegate paths: ${invalidPaths.joinToString()}")
        }

        val generatedModulesDir = installDir.resolve("generated/modules").createDirectories()
        val generatedDelegateDir = generatedModulesDir.resolve(delegate.name ?: "zilla.delegate").createDirectories()

        // Ensure write permissions
        if (!Files.isWritable(generatedModulesDir) || !Files.isWritable(generatedDelegateDir)) {
            feedback?.invoke("❌ No write permissions for $generatedModulesDir or $generatedDelegateDir")
            throw IOException("No write permissions for $generatedModulesDir or $generatedDelegateDir")
        }

        // Merge services and filter entries
        val services = mutableMapOf<String, MutableList<String>>()
        val entryNames = mutableSetOf<String>()
        val excludedPackage = Paths.get("org", "eclipse", "yasson", "internal", "components")
        val excludedClass = "BeanManagerInstanceCreator"

        // Ensure targetJar's parent directory exists
        Files.createDirectories(targetJar.parent)
        feedback?.invoke("✅ Created parent directory for targetJar: ${targetJar.parent}")

        // Create zilla.delegate.jar with all entries
        JarOutputStream(Files.newOutputStream(targetJar)).use { jar ->
            for (path in delegate.paths) {
                feedback?.invoke("📄 Processing JAR: $path")
                JarFile(path.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { artifactJar ->
                    artifactJar.entries().asSequence().forEach { entry ->
                        val entryPath = Paths.get(entry.name)
                        if (entry.name == "module-info.class" ||
                            entry.name == "META-INF/versions/9/module-info.class" ||
                            entryPath.endsWith("package-info.class") ||
                            (entryPath.startsWith(excludedPackage) && entryPath.fileName.toString().startsWith(excludedClass))
                        ) {
                            feedback?.invoke("⚠️ Skipping entry: ${entry.name}")
                            return@forEach
                        }
                        if (entryPath.startsWith(Paths.get("META-INF", "services")) &&
                            entryPath.nameCount - Paths.get("META-INF", "services").nameCount == 1
                        ) {
                            val serviceName = entryPath.fileName.toString()
                            val serviceImpl = artifactJar.getInputStream(entry).readAllBytes().toString(Charsets.UTF_8)
                            feedback?.invoke("✅ Found service: $serviceName with implementations: $serviceImpl")
                            services.computeIfAbsent(serviceName) { mutableListOf() }
                                .addAll(serviceImpl.split("\n").filter { it.isNotBlank() })
                        } else if (entryNames.add(entry.name)) {
                            feedback?.invoke("✅ Adding entry: ${entry.name}")
                            jar.putNextEntry(JarEntry(entry.name))
                            if (!entry.isDirectory) {
                                artifactJar.getInputStream(entry).use { jar.write(it.readAllBytes()) }
                            }
                            jar.closeEntry()
                        } else {
                            feedback?.invoke("⚠️ Duplicate entry skipped: ${entry.name}")
                        }
                    }
                }
            }
            services.forEach { (name, impls) ->
                val servicePath = Paths.get("META-INF/services/$name")
                val entry = JarEntry(servicePath.toString()).apply { time = 318240000000L }
                feedback?.invoke("✅ Writing merged service: $name with ${impls.size} implementations")
                jar.putNextEntry(entry)
                jar.write(impls.joinToString("\n").toByteArray(Charsets.UTF_8))
                jar.closeEntry()
            }
        }
        feedback?.invoke("✅ Created zilla.delegate.jar: $targetJar")

        // Verify targetJar exists and contains valid classes
        if (!Files.exists(targetJar)) {
            feedback?.invoke("❌ zilla.delegate.jar was not created: $targetJar")
            throw IOException("zilla.delegate.jar was not created: $targetJar")
        }
        var hasClasses = false
        JarFile(targetJar.toFile()).use { jar ->
            hasClasses = jar.entries().asSequence().any { it.name.endsWith(".class") && it.name != "module-info.class" }
        }
        if (!hasClasses) {
            feedback?.invoke("❌ zilla.delegate.jar contains no valid class files")
            throw IOException("zilla.delegate.jar contains no valid class files")
        }

        // Generate module-info.java
        feedback?.invoke("🛠 Running jdeps to generate module-info.java")
        val jdeps = ToolProvider.findFirst("jdeps").orElseThrow { IllegalStateException("jdeps not found") }
        val javaVersion = System.getProperty("java.version")
        feedback?.invoke("📜 Using JDK version: $javaVersion")
        val jdepsArgs = mutableListOf(
            "--generate-module-info", generatedDelegateDir.toString(),
            "--module", delegate.name ?: "io.aklivity.zilla.manager.delegate",
            "--module-path", modulesDir.toString(),
            targetJar.toString()
        )
        if (ignoreMissingDependencies) jdepsArgs.add(0, "--ignore-missing-deps")
        feedback?.invoke("📜 jdeps command: ${jdepsArgs.joinToString(" ")}")
        val jdepsOut = ByteArrayOutputStream()
        val jdepsErr = ByteArrayOutputStream()
        // Clear generated directory recursively
        Files.walk(generatedModulesDir).sorted(Comparator.reverseOrder()).skip(1).forEach { path ->
            try {
                Files.deleteIfExists(path)
            } catch (e: DirectoryNotEmptyException) {
                Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
        feedback?.invoke("✅ Cleared $generatedModulesDir for jdeps")
        var jdepsExitCode = jdeps.run(PrintStream(jdepsOut, true, Charsets.UTF_8), PrintStream(jdepsErr, true, Charsets.UTF_8), *jdepsArgs.toTypedArray())
        feedback?.invoke("✅ jdeps output: ${jdepsOut.toString(Charsets.UTF_8)}")
        if (jdepsExitCode != 0 || jdepsErr.toString(Charsets.UTF_8).isNotBlank()) {
            feedback?.invoke("❌ jdeps failed with exit code $jdepsExitCode: ${jdepsErr.toString(Charsets.UTF_8)}")
            throw IOException("jdeps failed with exit code $jdepsExitCode: ${jdepsErr.toString(Charsets.UTF_8)}")
        }

        // Check for module-info.java in generatedDelegateDir
        val targetModuleInfo = generatedDelegateDir.resolve("module-info.java")
        if (!Files.exists(targetModuleInfo)) {
            // Retry jdeps if module-info.java is missing
            var retries = 5
            while (retries > 0 && !Files.exists(targetModuleInfo)) {
                feedback?.invoke("⚠️ module-info.java not found at $targetModuleInfo, retrying jdeps")
                logger.debug("module-info.java not found at $targetModuleInfo, retrying")
                Files.walk(generatedModulesDir).sorted(Comparator.reverseOrder()).skip(1).forEach { path ->
                    try {
                        Files.deleteIfExists(path)
                    } catch (e: DirectoryNotEmptyException) {
                        Files.walk(path).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                    }
                }
                jdepsExitCode = jdeps.run(PrintStream(jdepsOut, true, Charsets.UTF_8), PrintStream(jdepsErr, true, Charsets.UTF_8), *jdepsArgs.toTypedArray())
                if (jdepsExitCode != 0 || jdepsErr.toString(Charsets.UTF_8).isNotBlank()) {
                    feedback?.invoke("❌ jdeps retry failed with exit code $jdepsExitCode: ${jdepsErr.toString(Charsets.UTF_8)}")
                    throw IOException("jdeps retry failed with exit code $jdepsExitCode: ${jdepsErr.toString(Charsets.UTF_8)}")
                }
                Thread.sleep(100)
                retries--
            }
        }
        if (!Files.exists(targetModuleInfo)) {
            // Check for module-info.java in unexpected subdirectories
            val moduleInfo = Files.walk(generatedModulesDir)
                .filter { it.fileName.toString() == "module-info.java" }
                .findFirst()
            if (moduleInfo.isPresent) {
                feedback?.invoke("📜 Found module-info.java at ${moduleInfo.get()}, moving to $targetModuleInfo")
                try {
                    Files.move(moduleInfo.get(), targetModuleInfo, StandardCopyOption.REPLACE_EXISTING)
                } catch (e: NoSuchFileException) {
                    feedback?.invoke("❌ Failed to move module-info.java: ${e.message}")
                    throw e
                }
            } else {
                feedback?.invoke("❌ module-info.java not found in $generatedModulesDir after retries")
                throw IOException("module-info.java not found in $generatedModulesDir")
            }
        }
        feedback?.invoke("✅ Generated module-info.java at $targetModuleInfo")

        // Validate and patch module-info.java
        var content = Files.readString(targetModuleInfo, Charsets.UTF_8)
        feedback?.invoke("📜 module-info.java content:\n$content")
        // Remove invalid requires clauses (e.g., com.ibm.icu)
        content = content.replace(Regex("requires\\s+com\\.ibm\\.icu;"), "")
        Files.writeString(targetModuleInfo, content)
        feedback?.invoke("✅ Removed invalid requires clauses from module-info.java")

        // Patch with uses clauses
        val uses = Regex("provides\\s+([^\\s]+)\\s+with").findAll(content).map { "uses ${it.groupValues[1]};" }.toList()
        if (uses.isNotEmpty()) {
            feedback?.invoke("✅ Patching module-info.java with ${uses.size} uses clauses")
            content = content.replace("}", "${uses.joinToString("\n")}\n}")
            Files.writeString(targetModuleInfo, content)
        } else {
            feedback?.invoke("⚠️ No provides clauses found to patch in module-info.java")
        }

        feedback?.invoke("📂 Expanding $targetJar for compilation")
        expandJar(targetJar, generatedDelegateDir).getOrElse { throw it }

        // Compile module-info.java
        feedback?.invoke("🛠 Compiling module-info.java")
        val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }
        // Include delegate.paths in module-path to resolve dependencies like com.ibm.icu
        val modulePath = listOf(modulesDir.toString()) + delegate.paths.map { it.parent.toString() }.distinct().joinToString(File.pathSeparator)
        val javacArgs = mutableListOf<String>()
        if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
        javacArgs.addAll(listOf("--module-path", modulePath, "-d", generatedDelegateDir.toString(), targetModuleInfo.toString()))
        feedback?.invoke("📜 javac command: ${javacArgs.joinToString(" ")}")
        val javacOut = ByteArrayOutputStream()
        val javacErr = ByteArrayOutputStream()
        val javacExitCode = javac.run(PrintStream(javacOut, true, Charsets.UTF_8), PrintStream(javacErr, true, Charsets.UTF_8), *javacArgs.toTypedArray())
        if (javacExitCode != 0) {
            feedback?.invoke("❌ javac failed with exit code $javacExitCode: ${javacErr.toString(Charsets.UTF_8)}")
            throw IOException("javac failed with exit code $javacExitCode: ${javacErr.toString(Charsets.UTF_8)}")
        }
        feedback?.invoke("✅ javac output: ${javacOut.toString(Charsets.UTF_8)}")

        // Check for module-info.class or multi-release module-info.class
        val moduleInfoClass: Path = generatedDelegateDir.resolve("module-info.class")
        val multiReleaseModuleInfo: Path = generatedDelegateDir.resolve("META-INF/versions/9/module-info.class")
        val (realModuleInfo: Path, entryName: String) = when {
            moduleInfoClass.exists() -> moduleInfoClass to "module-info.class"
            multiReleaseModuleInfo.exists() -> multiReleaseModuleInfo to "META-INF/versions/9/module-info.class"
            else -> {
                feedback?.invoke("❌ module-info.class not found in $generatedDelegateDir")
                throw IOException("module-info.class not found for delegate")
            }
        }
        feedback?.invoke("✅ Found module-info.class at $realModuleInfo")

        val finalJar = modulesDir.resolve("zilla.delegate.jar")
        val moduleInfoEntry = JarEntry(entryName).apply { time = 318240000000L }
        feedback?.invoke("📦 Extending $targetJar with $entryName")
        extendJar(targetJar, finalJar, moduleInfoEntry, realModuleInfo).getOrElse { throw it }
        feedback?.invoke("✅ Generated final delegate JAR: $finalJar")
    }.mapLeft {
        it.printStackTrace()
        feedback?.invoke("❌ Failed to generate delegate: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to generate delegate: ${it.message ?: "Unknown error"}")
    } as Either<ZpmResolutionErrorKt, Unit>

    private fun discoverModules(
        artifacts: List<ZpmArtifactKt>,
        feedback: ((String) -> Unit)?
    ): Collection<ZpmModuleKt> {
        feedback?.invoke("🔍 Discovering modules from ${artifacts.size} artifacts")
        val modules = mutableListOf<ZpmModuleKt>()
        val systemModulePrefixes = setOf("java.", "jdk.")

        artifacts.forEach { artifact ->
            val coordinate = artifact.id.toString()
            if (!isValidArtifactCoordinate(coordinate)) {
                feedback?.invoke("⚠️ Skipping invalid artifact coordinate: $coordinate")
                println("DEBUG: Skipping invalid artifact coordinate: $coordinate")
                return@forEach
            }

            feedback?.invoke("📄 Processing artifact: $coordinate")
            val moduleFinder = ModuleFinder.of(artifact.path)
            val moduleRefs = moduleFinder.findAll()

            if (moduleRefs.isEmpty()) {
                feedback?.invoke("✅ Found unnamed module for $coordinate")
                println("DEBUG: Found unnamed module for $coordinate")
                modules.add(ZpmModuleKt(artifact))
            } else {
                moduleRefs.forEach { moduleRef ->
                    val descriptor = moduleRef.descriptor()
                    val moduleName = descriptor.name()
                    if (systemModulePrefixes.any { moduleName.startsWith(it) }) {
                        feedback?.invoke("⚠️ Skipping system module: $moduleName")
                        println("DEBUG: Skipping system module: $moduleName")
                        return@forEach
                    }
                    feedback?.invoke("✅ Found module: $moduleName")
                    println("DEBUG: Found module: $moduleName")
                    modules.add(ZpmModuleKt(
                        descriptor,
                        artifact
                    ))
                }
            }
        }

        feedback?.invoke("✅ Discovered ${modules.size} modules")
        println("DEBUG: Discovered ${modules.size} modules")
        return modules
    }

    private fun isValidArtifactCoordinate(coordinate: String): Boolean {
        val parts = coordinate.split(":")
        val systemModulePrefixes = setOf("java.", "jdk.")
        return parts.size == 3 && parts.all { it.isNotBlank() } && !systemModulePrefixes.any { coordinate.startsWith(it) }
    }

    private fun migrateUnnamed(
        modules: MutableCollection<ZpmModuleKt>,
        delegate: ZpmModuleKt
    ): Either<NonEmptyList<String>, Collection<Path>> {
        feedback?.invoke("🚚 Starting migration of unnamed modules to delegate")
        logger.debug("Invoking migrateUnnamed with ${modules.size} modules")
        println("DEBUG: Invoking migrateUnnamed with ${modules.size} modules")
        val errors = mutableListOf<String>()
        val migratedPaths = mutableSetOf<Path>()
        val iterator = modules.iterator()
        var migratedCount = 0
        while (iterator.hasNext()) {
            val module = iterator.next()
            if (module.name == null) {
                module.paths.forEach { path ->
                    if (delegate.paths.add(path)) {
                        migratedPaths.add(path)
                        feedback?.invoke("✅ Migrated path $path to delegate")
                        logger.debug("Logged migration feedback for path: $path")
                        println("DEBUG: Feedback invoked for path: $path")
                    } else {
                        errors.add("Duplicate path in delegate: $path")
                        feedback?.invoke("⚠️ Duplicate path in delegate: $path")
                        logger.debug("Logged duplicate path warning: $path")
                        println("DEBUG: Duplicate path warning: $path")
                    }
                }
                iterator.remove()
                migratedCount++
            }
        }
        if (migratedCount == 0) {
            feedback?.invoke("⚠️ No unnamed modules found to migrate")
            logger.debug("No unnamed modules found to migrate")
            println("DEBUG: No unnamed modules found to migrate")
        } else {
            feedback?.invoke("✅ Migrated $migratedCount unnamed modules to delegate")
            logger.debug("Completed migration of $migratedCount unnamed modules")
            println("DEBUG: Completed migration of $migratedCount unnamed modules")
        }
        return if (errors.isNotEmpty()) {
            errors.toNonEmptyListOrNull()?.let { it.left() } ?: migratedPaths.right()
        } else {
            migratedPaths.right()
        }
    }

    private fun delegateAutomatic(
        modules: Collection<ZpmModuleKt>,
        delegate: ZpmModuleKt
    ): Either<NonEmptyList<String>, Unit> {
        feedback?.invoke("🔗 Starting delegation of automatic modules")
        val errors = mutableListOf<String>()
        val modulesMap = modules.associateBy { it.id }
        var delegatedCount = 0
        modules.forEach { module ->
            if (module.automatic) {
                feedback?.invoke("📄 Processing automatic module: ${module.name}")
                modulesMap[module.id]?.let { resolved ->
                    delegateModule(delegate, resolved, { id -> modulesMap[id] }, errors)
                    delegatedCount++
                    feedback?.invoke("✅ Delegated module: ${module.name}")
                } ?: run {
                    val error = "Skipping automatic module without delegate: ${module.name}"
                    errors.add(error)
                    feedback?.invoke("⚠️ $error")
                }
            }
        }
        feedback?.invoke("✅ Delegated $delegatedCount automatic modules")
        modules.filter { it.automatic && !it.delegating }.forEach { module ->
            val error = "Unresolved automatic module: ${module.name}"
            errors.add(error)
            feedback?.invoke("⚠️ $error")
        }
        return if (errors.isNotEmpty()) {
            errors.toNonEmptyListOrNull()?.let { it.left() } ?: Unit.right()
        } else {
            Unit.right()
        }
    }

    private fun delegateModule(
        delegate: ZpmModuleKt,
        module: ZpmModuleKt,
        lookup: (ZpmArtifactIdKt?) -> ZpmModuleKt?,
        errors: MutableList<String>
    ) {
        if (!module.delegating) {
            feedback?.invoke("🔗 Delegating module: ${module.name ?: module.id}")
            module.paths.forEach { path ->
                if (delegate.paths.add(path)) {
                    feedback?.invoke("✅ Added path $path to delegate")
                } else {
                    val error = "Duplicate path in delegate: $path"
                    errors.add(error)
                    feedback?.invoke("⚠️ $error")
                }
            }
            module.paths.clear()
            module.delegating = true
            module.depends.forEach { depend ->
                val dependId = ZpmArtifactIdKt(depend.groupId, depend.artifactId, depend.version.fold({ "LATEST" }, { it }))
                lookup(dependId)?.let { resolvedDepend ->
                    delegateModule(delegate, resolvedDepend, lookup, errors)
                    feedback?.invoke("✅ Processed dependency: $dependId")
                } ?: feedback?.invoke("⚠️ Dependency not found: $dependId")
            }
        }
    }

    private fun expandJar(sourcePath: Path, targetDir: Path): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("📂 Starting expansion of $sourcePath to $targetDir")
        if (!Files.exists(sourcePath) || Files.size(sourcePath) < 32) {
            feedback?.invoke("❌ Source JAR missing or too small: $sourcePath")
            throw IOException("Source JAR missing or too small: $sourcePath")
        }
        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would expand $sourcePath to $targetDir")
            Files.createDirectories(targetDir)
            return@catch
        }
        JarFile(sourcePath.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { sourceJar ->
            sourceJar.entries().asSequence().forEach { entry ->
                val entryPath = targetDir.resolve(entry.name).normalize()
                feedback?.invoke("📄 Processing entry: ${entry.name}")
                if (!entryPath.startsWith(targetDir)) {
                    feedback?.invoke("❌ Bad zip entry: ${entry.name}")
                    throw IOException("Bad zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    Files.createDirectories(entryPath)
                    feedback?.invoke("✅ Created directory: $entryPath")
                } else {
                    val parentPath = entryPath.parent
                    if (!Files.exists(parentPath)) {
                        Files.createDirectories(parentPath)
                        feedback?.invoke("✅ Created parent directory: $parentPath")
                    }
                    sourceJar.getInputStream(entry).use { input ->
                        Files.write(entryPath, input.readAllBytes())
                        feedback?.invoke("✅ Wrote file: $entryPath")
                    }
                }
            }
        }
    }.mapLeft {
        feedback?.invoke("❌ Failed to expand $sourcePath: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to expand $sourcePath: ${it.message}")
    }

    open fun extendJar(
        sourcePath: Path,
        targetPath: Path,
        newEntry: JarEntry,
        newEntryPath: Path
    ): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("📦 Starting extension of $sourcePath with ${newEntry.name}")
        if (!Files.exists(sourcePath) || Files.size(sourcePath) < 32) {
            feedback?.invoke("❌ Source JAR missing or too small: $sourcePath")
            throw IOException("Source JAR missing or too small: $sourcePath")
        }
        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would extend $sourcePath with ${newEntry.name}")
            Files.createDirectories(targetPath.parent)
            Files.writeString(targetPath, "// Dry-run extended JAR")
            return@catch
        }
        JarFile(sourcePath.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { sourceJar ->
            JarOutputStream(Files.newOutputStream(targetPath)).use { targetJar ->
                sourceJar.entries().asSequence().forEach { entry ->
                    targetJar.putNextEntry(entry)
                    feedback?.invoke("📄 Copying entry: ${entry.name}")
                    if (!entry.isDirectory) {
                        sourceJar.getInputStream(entry).use { input ->
                            targetJar.write(input.readAllBytes())
                        }
                    }
                    targetJar.closeEntry()
                    feedback?.invoke("✅ Copied entry: ${entry.name}")
                }

                var retries = 5
                var fileExists = Files.exists(newEntryPath)
                while (retries > 0 && !fileExists) {
                    feedback?.invoke("⚠️ New entry file not found at $newEntryPath, retrying")
                    logger.debug("New entry file not found at $newEntryPath, retrying")
                    Thread.sleep(100)
                    retries--
                    fileExists = Files.exists(newEntryPath)
                }
                if (!fileExists) {
                    feedback?.invoke("❌ New entry file still not found at $newEntryPath after retries")
                    throw IOException("New entry file not found at $newEntryPath")
                }
                targetJar.putNextEntry(newEntry)
                targetJar.write(Files.readAllBytes(newEntryPath))
                targetJar.closeEntry()
                feedback?.invoke("✅ Added new entry: ${newEntry.name}")
            }
        }
    }.mapLeft {
        feedback?.invoke("❌ Failed to extend $sourcePath: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to extend $sourcePath: ${it.message}")
    } as Either<ZpmResolutionErrorKt, Unit>

    fun compileModuleInfo(moduleInfoPath: Path): Either<ZpmResolutionErrorKt, Path> = Either.catch {
        feedback?.invoke("🛠 Starting compilation of $moduleInfoPath to module-info.class")
        val moduleInfoClass = moduleInfoPath.parent.resolve("module-info.class")
        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would compile $moduleInfoPath to $moduleInfoClass")
            if (!Files.exists(moduleInfoClass)) {
                Files.writeString(moduleInfoClass, "// Dry-run module-info.class")
            }
            feedback?.invoke("✅ Compiled $moduleInfoClass")
            return@catch moduleInfoClass
        }
        if (!Files.exists(moduleInfoPath)) {
            feedback?.invoke("❌ module-info.java not found: $moduleInfoPath")
            throw IOException("module-info.java not found: $moduleInfoPath")
        }
        val javac = ToolProvider.findFirst("javac").orElseThrow {
            feedback?.invoke("❌ javac tool not found")
            RuntimeException("javac tool not found")
        }
        val args = arrayOf(moduleInfoPath.toString(), "-d", moduleInfoPath.parent.toString())
        feedback?.invoke("📜 Running javac ${args.joinToString(" ")}")
        val javacOut = ByteArrayOutputStream()
        val javacErr = ByteArrayOutputStream()
        val exitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *args)
        if (exitCode != 0) {
            feedback?.invoke("❌ javac failed with exit code $exitCode: ${javacErr.toString(Charsets.UTF_8)}")
            throw RuntimeException("javac failed with exit code $exitCode")
        }
        if (!Files.exists(moduleInfoClass)) {
            feedback?.invoke("❌ module-info.class was not created")
            throw RuntimeException("module-info.class was not created")
        }
        feedback?.invoke("✅ Compiled $moduleInfoClass")
        moduleInfoClass
    }.mapLeft {
        feedback?.invoke("❌ Failed to compile module-info: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to compile module-info: ${it.message}")
    }

    open fun writeLockFile(templatePath: Path, artifacts: List<ZpmArtifactKt>): Path {
        val lockPath = installDir.resolve("zpm.lock")
        feedback?.invoke("📝 Preparing lock file with ${artifacts.size} artifacts")
        val lines = artifacts.map { "${it.id} -> ${it.path}" }
        try {
            Files.createDirectories(lockPath.parent)
            if (dryRun) {
                Files.writeString(lockPath, "// Dry-run zpm.lock\n${lines.joinToString("\n")}")
            } else {
                Files.write(lockPath, lines)
            }
            if (verbose) feedback?.invoke("✅ Wrote lock file to $lockPath")
        } catch (ex: Exception) {
            feedback?.invoke("⚠️ Failed to write lock file: ${ex.message}")
            logger.warn("Failed to write lock file: ${ex.message}")
        }
        return lockPath
    }

    open fun generateSystemOnlyAutomatic(modules: Collection<ZpmModuleKt>, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("⚙️ Generating system-only automatic modules")
        val promotions = mutableMapOf<ZpmModuleKt, Path>()
        val jdeps = ToolProvider.findFirst("jdeps").orElseThrow { IllegalStateException("jdeps not found") }
        val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }

        for (module in modules) {
            if (!module.automatic || module.depends.isNotEmpty()) continue
            val artifactPath = module.paths.first()
            if (jarIsModular(artifactPath)) {
                feedback?.invoke("✅ Skipping already-modular JAR: ${module.name}")
                continue
            }

            val generatedModulesDir = installDir.resolve("generated/modules").createDirectories()
            val generatedModuleDir = generatedModulesDir.resolve(module.name ?: "unnamed").createDirectories()
            val jdepsArgs = mutableListOf("--generate-open-module")
            if (ignoreMissingDependencies) jdepsArgs.add("--ignore-missing-deps")
            jdepsArgs.add(generatedModulesDir.toString())
            jdepsArgs.add(artifactPath.toString())

            val jdepsOut = ByteArrayOutputStream()
            val jdepsErr = ByteArrayOutputStream()
            val exitCode = jdeps.run(PrintStream(jdepsOut), PrintStream(jdepsErr), *jdepsArgs.toTypedArray())
            if (exitCode != 0 && !ignoreMissingDependencies) {
                feedback?.invoke("❌ jdeps failed for ${module.name}: ${jdepsErr.toString(Charsets.UTF_8)}")
                throw IOException("jdeps failed for ${module.name}")
            }
            feedback?.invoke("✅ jdeps output: ${jdepsOut.toString(Charsets.UTF_8)}")

            val generatedModuleInfo = generatedModuleDir.resolve("module-info.java")
            if (!generatedModuleInfo.exists()) {
                feedback?.invoke("⚠️ module-info.java not found for ${module.name}")
                continue
            }

            expandJar(artifactPath, generatedModuleDir).getOrElse { throw it }
            val javacArgs = mutableListOf<String>()
            if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
            javacArgs.addAll(listOf("-d", generatedModuleDir.toString(), generatedModuleInfo.toString()))

            val javacOut = ByteArrayOutputStream()
            val javacErr = ByteArrayOutputStream()
            val javacExitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
            if (javacExitCode != 0) {
                feedback?.invoke("❌ javac failed for ${module.name}: ${javacErr.toString(Charsets.UTF_8)}")
                throw IOException("javac failed for ${module.name}")
            }
            feedback?.invoke("✅ javac output: ${javacOut.toString(Charsets.UTF_8)}")

            val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
            val multiReleaseModuleInfo = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
            val (realModuleInfo, entryName) = when {
                moduleInfoClass.exists() -> moduleInfoClass to "module-info.class"
                multiReleaseModuleInfo.exists() -> multiReleaseModuleInfo to "META-INF/versions/9/module-info.class"
                else -> throw IOException("module-info.class not found for ${module.name}")
            }

            val generatedModulePath = generatedModulesDir.resolve("${module.name}.jar")
            val moduleInfoEntry = JarEntry(entryName).apply { time = 318240000000L }
            extendJar(artifactPath, generatedModulePath, moduleInfoEntry, realModuleInfo).getOrElse { throw it }
            promotions[module] = generatedModulePath
        }

        modules.minus(promotions.keys)
        promotions.forEach { (module, newPath) ->
            val descriptor = moduleDescriptor(newPath) ?: throw IOException("Failed to load descriptor for ${module.name}")
            modules.plus(ZpmModuleKt(
                descriptor = descriptor,
                artifact = ZpmArtifactKt(module.id!!, newPath, setOf())
            ))
        }
    }.mapLeft {
        feedback?.invoke("❌ Failed to generate system-only automatic modules: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error")
    }

    open fun jarIsModular(jarPath: Path): Boolean = Either.catch {
        FileSystems.newFileSystem(jarPath, null as ClassLoader?).use { fs ->
            fs.getPath("/module-info.class").exists() || fs.getPath("/META-INF/versions/9/module-info.class").exists()
        }
    }.getOrElse { false }

    open fun atLeastVersion(tool: ToolProvider, major: Int): Boolean {
        val out = ByteArrayOutputStream()
        tool.run(PrintStream(out), System.err, "--version")
        val matcher = Regex("""(\d+)\.""").find(out.toString(Charsets.UTF_8))
        return matcher?.groups?.get(1)?.value?.toIntOrNull()?.let { it >= major } ?: false
    }

    open fun generateDelegating(modules: Collection<ZpmModuleKt>, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("📝 Generating delegating modules")
        val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }
        val generatedModulesDir = installDir.resolve("generated/modules").createDirectories()

        for (module in modules.filter { it.delegating }) {
            val generatedModuleDir = generatedModulesDir.resolve(module.name ?: "unnamed").createDirectories()
            val moduleInfo = generatedModuleDir.resolve("module-info.java")
            Files.write(moduleInfo, """
            open module ${module.name} {
                requires transitive zilla.delegate;
            }
        """.trimIndent().toByteArray())

            val javacArgs = mutableListOf<String>()
            if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
            javacArgs.addAll(listOf("--module-path", modulesDir.toString(), "-d", generatedModuleDir.toString(), moduleInfo.toString()))

            val javacOut = ByteArrayOutputStream()
            val javacErr = ByteArrayOutputStream()
            val exitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
            if (exitCode != 0) {
                feedback?.invoke("❌ javac failed for ${module.name}: ${javacErr.toString(Charsets.UTF_8)}")
                throw IOException("javac failed for ${module.name}")
            }
            feedback?.invoke("✅ javac output: ${javacOut.toString(Charsets.UTF_8)}")

            val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
            val multiReleaseModuleInfo = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
            val (realModuleInfo, entryName) = when {
                moduleInfoClass.exists() -> moduleInfoClass to "module-info.class"
                multiReleaseModuleInfo.exists() -> multiReleaseModuleInfo to "META-INF/versions/9/module-info.class"
                else -> {
                    feedback?.invoke("❌ module-info.class not found in $generatedModuleDir")
                    throw IOException("module-info.class not found for ${module.name}")
                }
            }
            feedback?.invoke("✅ Found module-info.class at $realModuleInfo")

            val modulePath = modulesDir.resolve("${module.name}.jar")
            JarOutputStream(Files.newOutputStream(modulePath)).use { jar ->
                val entry = JarEntry(entryName).apply { time = 318240000000L }
                jar.putNextEntry(entry)
                jar.write(Files.readAllBytes(realModuleInfo))
                jar.closeEntry()
            }
            feedback?.invoke("✅ Generated delegating module: ${module.name}")
        }
    }.mapLeft {
        feedback?.invoke("❌ Failed to generate delegating modules: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error")
    }

    private fun checkTemplate(templatePath: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        if (!Files.exists(templatePath)) {
            feedback?.invoke("❌ Template file not found: $templatePath")
            throw IOException("Template file not found: $templatePath")
        }
        if (!Files.isReadable(templatePath)) {
            feedback?.invoke("❌ Template file not readable: $templatePath")
            throw IOException("Template file not readable: $templatePath")
        }
    }.mapLeft {
        feedback?.invoke("❌ Failed to check template: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to check template: ${it.message}")
    }.map { Unit }

    private fun linkImageAndWriteLauncher(targetJar: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Path> = Either.catch {
        feedback?.invoke("🔗 Linking image for $targetJar")
        val imagePath = installDir.resolve("image")
        imageLinker.link(targetJar, imagePath).getOrElse { throw it }
        feedback?.invoke("✅ Linked image to $imagePath")

        feedback?.invoke("📝 Writing launcher")
        val launcherPath = installDir.resolve("zilla.bat")
        launcherWriter.write(ZpmModuleKt.DELEGATE_NAME, installDir).getOrElse { throw it }
        feedback?.invoke("✅ Wrote launcher to $launcherPath")
        targetJar
    }.mapLeft {
        feedback?.invoke("❌ Failed to link image or write launcher: ${it.message}")
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to link image or write launcher: ${it.message}")
    }
}