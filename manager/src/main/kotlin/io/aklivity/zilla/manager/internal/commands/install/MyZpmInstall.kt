package io.aklivity.zilla.manager.internal.commands.install

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import io.aklivity.zilla.manager.internal.commands.install.cache.*
import io.aklivity.zilla.manager.internal.commands.install.impl.*
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmModuleKt
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.module.ModuleFinder
import java.nio.file.*
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile
import java.util.spi.ToolProvider
import kotlin.io.path.*

class MyZpmInstall(
    private val cache: ZpmCacheKt,
    private val installDir: Path,
    private val jarCopier: JarCopier,
    private val manifestMerger: ManifestMerger,
    private val moduleInfoGenerator: ModuleInfoGenerator,
    private val imageLinker: DefaultImageLinker,
    private val launcherWriter: DefaultLauncherWriter,
    private val dryRun: Boolean = false,
    private val verbose: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) {
    private val logger = LoggerFactory.getLogger(MyZpmInstall::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val modulesDir: Path = installDir.resolve("modules")

    private fun ZpmError.toResolutionError(): ZpmResolutionErrorKt =
        ZpmResolutionErrorKt.DependencyResolutionError(this.toString())

    private fun Path.nameWithoutExtension(): String =
        fileName.toString().substringBeforeLast(".")

    // Refactored installFromTemplate
    fun installFromTemplate(templatePath: Path, feedback: ((String) -> Unit)? = null): Either<ZpmResolutionErrorKt, Path> {
        return checkTemplate(templatePath, feedback)
            .flatMap { parseTemplate(templatePath, feedback) }
            .flatMap { template -> resolveDependencies(template, feedback) }
            .flatMap { artifacts -> discoverAndMigrateModules(artifacts, feedback) }
            .flatMap { delegate -> processModules(delegate, feedback) }
            .flatMap { targetJar -> generateAndCompileModuleInfo(targetJar, feedback) }
            .flatMap { targetJar -> linkImageAndWriteLauncher(targetJar, feedback) }
    }

    // Step 1: Check if template exists
    private fun checkTemplate(templatePath: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Unit> {
        feedback?.invoke("📂 Checking template: $templatePath")
        return if (templatePath.exists()) {
            Either.Right(Unit)
        } else {
            feedback?.invoke("❌ Template not found: $templatePath")
            ZpmResolutionErrorKt.ArtifactNotFound(templatePath.toString()).left()
        }
    }

    // Step 2: Parse the template
    private fun parseTemplate(templatePath: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, ZpmTemplate> {
        feedback?.invoke("📜 Parsing template: $templatePath")
        return Either.catch {
            Json.decodeFromString<ZpmTemplate>(Files.readString(templatePath))
        }.mapLeft {
            feedback?.invoke("❌ Failed to parse template: ${it.message}")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to parse template: ${it.message}")
        }
    }

    // Step 3: Resolve dependencies
    private fun resolveDependencies(template: ZpmTemplate, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> {
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

    // Step 4: Discover and migrate modules
    private fun discoverAndMigrateModules(artifacts: List<ZpmArtifactKt>, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, ZpmModuleKt> {
        val delegate = ZpmModuleKt(name = null, id = null, paths = mutableSetOf())
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

    // Step 5: Process modules (expand and copy JARs)
    private fun processModules(
        delegate: ZpmModuleKt,
        feedback: ((String) -> Unit)?
    ): Either<ZpmResolutionErrorKt, Path> {
        val modulePaths = delegate.paths.flatMap { it }.toSet()
        if (modulePaths.isEmpty()) {
            feedback?.invoke("⚠️ No modules to process, skipping JAR creation")
            return ZpmResolutionErrorKt.NoModulesFound("No modules found to create JAR").left()
        }

        val targetJar = installDir.resolve("zilla-install.jar")
        feedback?.invoke("📦 Starting copy of ${modulePaths.size} JARs to $targetJar")
        return jarCopier.copyJars(modulePaths.toList(), targetJar).mapLeft {
            feedback?.invoke("❌ Failed to copy JARs: $it")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to copy JARs: $it")
        }.map {
            feedback?.invoke("✅ Copied JARs to $targetJar")
            targetJar
        }
    }

    // Step 6: Generate and compile module-info
    private fun generateAndCompileModuleInfo(targetJar: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Path> {
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

    // Step 7: Link image and write launcher
    private fun linkImageAndWriteLauncher(targetJar: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Path> {
        feedback?.invoke("🔗 Linking runtime image")
        return imageLinker.link(listOf(targetJar), installDir).mapLeft {
            feedback?.invoke("❌ Failed to link image: ${it.toString()}")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to link image: ${it.toString()}")
        }.flatMap { imagePath ->
            feedback?.invoke("✅ Linked runtime image: $imagePath")
            feedback?.invoke("📝 Writing lock file")
            val lockPath = writeLockFile(imagePath, emptyList()) // Adjust based on actual artifacts usage
            feedback?.invoke("✅ Wrote lock file: $lockPath")
            feedback?.invoke("📜 Generating launcher")
            launcherWriter.write("io.aklivity.zilla.runtime.command", installDir).mapLeft {
                feedback?.invoke("❌ Failed to write launcher: ${it.toString()}")
                ZpmResolutionErrorKt.DependencyResolutionError("Failed to write launcher: ${it.toString()}")
            }.map { launcherPath ->
                feedback?.invoke("✅ Generated launcher: $launcherPath")
                launcherPath
            }
        }
    }

    private fun discoverModules(
        artifacts: List<ZpmArtifactKt>,
        feedback: ((String) -> Unit)?
    ): Collection<ZpmModuleKt> {
        feedback?.invoke("🔍 Discovering modules from ${artifacts.size} artifacts")
        val modules = mutableListOf<ZpmModuleKt>()

        artifacts.forEach { artifact ->
            val coordinate = artifact.id.toString()
            if (!isValidArtifactCoordinate(coordinate)) {
                feedback?.invoke("⚠️ Skipping invalid artifact coordinate: $coordinate")
                return@forEach
            }

            feedback?.invoke("📄 Processing artifact: $coordinate")
            val moduleFinder = ModuleFinder.of(artifact.path)
            val moduleRefs = moduleFinder.findAll()

            if (moduleRefs.isEmpty()) {
                feedback?.invoke("✅ Found unnamed module for $coordinate")
                modules.add(ZpmModuleKt(name = null, id = artifact.id, paths = mutableSetOf(artifact.path)))
            } else {
                moduleRefs.forEach { moduleRef ->
                    val descriptor = moduleRef.descriptor()
                    feedback?.invoke("✅ Found module: ${descriptor.name()}")
                    modules.add(ZpmModuleKt(name = descriptor.name(), id = artifact.id, paths = mutableSetOf(artifact.path)))
                }
            }
        }

        feedback?.invoke("✅ Discovered ${modules.size} modules")
        return modules
    }

    // Helper function to validate artifact coordinates
    private fun isValidArtifactCoordinate(coordinate: String): Boolean {
        val parts = coordinate.split(":")
        return parts.size == 3 && parts.all { it.isNotBlank() }
    }

    private fun migrateUnnamed(
        modules: MutableCollection<ZpmModuleKt>,
        delegate: ZpmModuleKt
    ): Either<NonEmptyList<String>, Collection<Path>> {
        feedback?.invoke("🚚 Starting migration of unnamed modules to delegate")
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
                    } else {
                        errors.add("Duplicate path in delegate: $path")
                        feedback?.invoke("⚠️ Duplicate path in delegate: $path")
                    }
                }
                iterator.remove()
                migratedCount++
            }
        }
        feedback?.invoke("✅ Migrated $migratedCount unnamed modules to delegate")
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
            module.depends.forEach { dependId ->
                lookup(dependId)?.let { depend ->
                    delegateModule(delegate, depend, lookup, errors)
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

    private fun extendJar(
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
                if (!Files.exists(newEntryPath)) {
                    feedback?.invoke("❌ New entry file not found: $newEntryPath")
                    throw IOException("New entry file not found: $newEntryPath")
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
        val exitCode = javac.run(System.out, System.err, *args)
        if (exitCode != 0) {
            feedback?.invoke("❌ javac failed with exit code $exitCode")
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

    private fun writeLockFile(templatePath: Path, artifacts: List<ZpmArtifactKt>): Path {
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
}
