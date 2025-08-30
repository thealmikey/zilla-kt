package io.aklivity.zilla.manager.internal.commands.install

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.flatMap
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import arrow.core.toNonEmptyListOrNull
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactIdKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModuleKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmCacheKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmDependencyKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultImageLinker
import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultLauncherWriter
import io.aklivity.zilla.manager.internal.commands.install.impl.JarCopier
import io.aklivity.zilla.manager.internal.commands.install.impl.ManifestMerger
import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.io.IOException
import java.lang.module.ModuleFinder
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.spi.ToolProvider
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isReadable
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
import kotlinx.serialization.json.Json
import java.lang.module.ModuleDescriptor
import java.nio.file.Paths
import kotlin.io.path.isWritable
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

open class MyZpmInstall(
    private val cache: ZpmCacheKt,
    private val installDir: Path,
    private val jarCopier: JarCopier,
    private val manifestMerger: ManifestMerger,
    private val moduleInfoGenerator: ModuleInfoGenerator = ModuleInfoGenerator(
        cacheDir = installDir.resolve("cache") // Pass .zpm/cache
    ),
    private val imageLinker: DefaultImageLinker,
    private val launcherWriter: DefaultLauncherWriter,
    private val dryRun: Boolean = false,
    private val verbose: Boolean = false,
    private val ignoreMissingDependencies: Boolean = true,
    val feedback: ((String) -> Unit)? = { x -> println(x) }
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val logger = LoggerFactory.getLogger(MyZpmInstall::class.java)
    private val modulesDir: Path = installDir.resolve("modules")
    private val generatedDir: Path = installDir.resolve("generated")
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private val retryDelay: Long = if (isWindows) 500L else 100L
    private val maxRetries: Int = 10

    private fun createEmptyJar(target: Path): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("🛠 Creating empty JAR: $target")
        Files.createDirectories(target.parent)
        JarOutputStream(Files.newOutputStream(target)).use { jar ->
            jar.putNextEntry(JarEntry("META-INF/MANIFEST.MF"))
            jar.write("Manifest-Version: 1.0\n".toByteArray())
            jar.closeEntry()
        }
        feedback?.invoke("✅ Created empty JAR: $target")
        logger.debug("Created empty JAR: $target")
    }.mapLeft {
        feedback?.invoke("❌ Failed to create empty JAR: ${it.message}")
        logger.error("Failed to create empty JAR", it)
        ZpmResolutionErrorKt.DependencyResolutionError("Failed to create empty JAR: ${it.message}")
    }

    open fun discoverModules(artifacts: List<ZpmArtifactKt>, feedback: ((String) -> Unit)?): List<ZpmModuleKt> {
        val modules = mutableListOf<ZpmModuleKt>()
        val systemModulePrefixes = setOf("java.", "jdk.")

        var injectHandled = false

        artifacts.forEach { artifact ->
            val coordinate = artifact.id.toString()
            logger.debug("Processing artifact coordinate: $coordinate")

            if (!isValidArtifactCoordinate(coordinate)) {
                feedback?.invoke("⚠️ Skipping invalid artifact coordinate: $coordinate")
                logger.warn("Skipping invalid artifact coordinate: $coordinate")
                return@forEach
            }

            if (!Files.exists(artifact.path)) {
                feedback?.invoke("⚠️ Skipping artifact with non-existent path: ${artifact.path}")
                logger.warn("Skipping artifact with non-existent path: ${artifact.path}")
                return@forEach
            }

            // 🔧 Patch multi-release JARs before discovery
            moduleInfoGenerator.patchMultiReleaseJar(artifact.path, feedback)

            feedback?.invoke("📄 Processing artifact: $coordinate")
            val moduleFinder = ModuleFinder.of(artifact.path)
            val moduleRefs = moduleFinder.findAll()

            if (moduleRefs.isEmpty()) {
                feedback?.invoke("✅ Found unnamed module for $coordinate")
                logger.debug("Found unnamed module for $coordinate")
                modules.add(ZpmModuleKt(artifact))
            } else {
                moduleRefs.forEach { moduleRef ->
                    val descriptor = moduleRef.descriptor()
                    val moduleName = descriptor.name()

                    if (moduleName == "java.inject") {
                        feedback?.invoke("📦 Special-casing java.inject module")
                        logger.debug("Special-casing java.inject")
                        injectHandled = true
                    }

                    if (systemModulePrefixes.any { moduleName.startsWith(it) } && !knownNonSystemButConfusing.contains(moduleName)) {
                        feedback?.invoke("⚠️ Skipping system module: $moduleName")
                        logger.debug("Skipping system module: $moduleName")
                        return@forEach
                    }

                    feedback?.invoke("✅ Found module: $moduleName (automatic=${descriptor.isAutomatic()})")
                    logger.debug("Found module: $moduleName (automatic=${descriptor.isAutomatic()})")

                    modules.add(
                        ZpmModuleKt(
                            name = moduleName,
                            id = artifact.id.copy(moduleName = moduleName),
                            paths = mutableSetOf(artifact.path),
                            depends = artifact.dependencies
                                .mapNotNull { req ->
                                    if (systemModulePrefixes.any { req.toString().startsWith(it) } && !knownNonSystemButConfusing.contains(moduleName)) null else req
                                }.toMutableSet(),
                            automatic = descriptor.isAutomatic(),
                            delegating = false
                        )
                    )
                }
            }
        }

        // 🆕 If no java.inject artifact found but needed, generate stub
        if (!injectHandled) {
            val target = Path.of(".zpm/modules/java.inject.stub.jar")
            feedback?.invoke("📦 Generating fallback java.inject stub: $target")
            logger.debug("Generating fallback java.inject stub at $target")
            moduleInfoGenerator.generateJavaInjectStub(target)

            val stubArtifact = ZpmArtifactKt(
                id = ZpmArtifactIdKt("java.inject", "stub", "1.0.0").copy(moduleName = "java.inject"),
                path = target,
                dependencies = mutableSetOf()
            )
            modules.add(ZpmModuleKt(ModuleDescriptor.newModule("java.inject").build(), stubArtifact))
        }

        feedback?.invoke("✅ Discovered ${modules.size} modules")
        logger.debug("Discovered modules: ${modules.map { it.name ?: it.id }}")
        return modules
    }


    private fun generateJavaInjectStub(target: Path) {
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



    private fun hasAnyModuleInfo(jarPath: Path): Boolean {
        return try {
            JarFile(jarPath.toFile()).use { jar ->
                jar.entries().asSequence().any { entry ->
                    val name = entry.name
                    !entry.isDirectory && (
                            name == "module-info.class" ||
                                    (name.startsWith("META-INF/versions/") && name.endsWith("module-info.class"))
                            )
                }
            }
        } catch (_: Exception) {
            false
        }
    }


    private fun isValidArtifactCoordinate(coordinate: String): Boolean {
        return coordinate.matches(Regex(".+:.+:.+"))
    }

    @OptIn(ExperimentalPathApi::class)
    private fun generateSystemOnlyAutomatic(
        modules: Collection<ZpmModuleKt>,
        feedback: ((String) -> Unit)?
    ): Either<NonEmptyList<String>, Unit> {
        feedback?.invoke("📝 Generating module-info for system-only automatic modules")
        logger.debug("Generating module-info for system-only automatic modules: ${modules.map { it.name ?: it.id }}")
        val errors = mutableListOf<String>()
        val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }
        val generatedModulesDir = generatedDir.resolve("modules").createDirectories()

        modules.filter { it.automatic && it.depends.all { dep -> isSystemModule(dep.toString()) } }.forEach { module ->
            if (module.name == null) {
                val error = "Unnamed system-only automatic module ${module.id} should be migrated"
                errors.add(error)
                feedback?.invoke("❌ $error")
                logger.error(error)
                return@forEach
            }
            if (module.paths.isEmpty()) {
                val error = "No artifact path for system-only automatic module ${module.name}"
                errors.add(error)
                feedback?.invoke("❌ $error")
                logger.error(error)
                return@forEach
            }

            if (dryRun) {
                feedback?.invoke("🧪 [dry-run] Would generate module-info for ${module.name}")
                logger.debug("[dry-run] Would generate module-info for ${module.name}")
                return@forEach
            }

            val generatedModuleDir = generatedModulesDir.resolve(module.name).createDirectories()
            logger.debug("Created generated module dir for ${module.name}: $generatedModuleDir")
            try {
                val sourcePath = module.paths.first()
                val moduleInfoPath = moduleInfoGenerator.makeModuleInfoForAutomaticJar(sourcePath, generatedModulesDir, module).getOrElse { ex ->
                    val error = "Failed to generate module-info for ${module.name}: ${ex.message}"
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error, ex)
                    return@forEach
                }
                logger.debug("Generated module-info at $moduleInfoPath")

                // Compile module-info.java
                val javacArgs = mutableListOf<String>()
                if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
                javacArgs.addAll(listOf("--module-path", modulesDir.toString(), "-d", generatedModuleDir.toString(), moduleInfoPath.toString()))
                feedback?.invoke("📜 javac command for ${module.name}: ${javacArgs.joinToString(" ")}")
                logger.debug("javac command: ${javacArgs.joinToString(" ")}")
                val javacOut = ByteArrayOutputStream()
                val javacErr = ByteArrayOutputStream()
                val exitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
                val javacOutStr = javacOut.toString(Charsets.UTF_8)
                val javacErrStr = javacErr.toString(Charsets.UTF_8)
                feedback?.invoke("📜 javac output for ${module.name}: $javacOutStr")
                if (javacErrStr.isNotEmpty()) {
                    feedback?.invoke("📜 javac error output for ${module.name}: $javacErrStr")
                    logger.debug("javac error: $javacErrStr")
                }
                logger.debug("javac output: $javacOutStr")
                if (exitCode != 0) {
                    val error = "javac failed for ${module.name}: $javacErrStr"
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error)
                    return@forEach
                }
                feedback?.invoke("✅ Compiled module-info for ${module.name}")

                // Check for module-info.class
                val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
                val multiReleaseModuleInfo = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
                val (realModuleInfo, entryName) = when {
                    moduleInfoClass.exists() -> moduleInfoClass to "module-info.class"
                    multiReleaseModuleInfo.exists() -> multiReleaseModuleInfo to "META-INF/versions/9/module-info.class"
                    else -> {
                        val error = "module-info.class not found for ${module.name}"
                        errors.add(error)
                        feedback?.invoke("❌ $error")
                        logger.error(error)
                        return@forEach
                    }
                }
                feedback?.invoke("✅ Found module-info.class at $realModuleInfo")
                logger.debug("Found module-info.class at $realModuleInfo with entry $entryName")

                // Copy and extend JAR
                val modulePath = modulesDir.resolve("${module.name}.jar")
                jarCopier.copyJars(listOf(sourcePath), modulePath).getOrElse { ex ->
                    val error = "Failed to copy JAR for ${module.name}: ${ex.message}"
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error, ex)
                    return@forEach
                }
                jarExtender(sourcePath, modulePath, JarEntry(entryName).apply { time = 318240000000L }, realModuleInfo).getOrElse { ex ->
                    val error = "Failed to extend JAR with module-info for ${module.name}: ${ex.message}"
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error, ex)
                    return@forEach
                }
                feedback?.invoke("✅ Promoted system-only automatic module ${module.name} to $modulePath")
                logger.debug("Promoted system-only automatic module ${module.name} to $modulePath")
                module.delegating = false // Treat as named module
            } catch (e: Exception) {
                if (Files.exists(generatedModuleDir)) {
                    generatedModuleDir.deleteRecursively()
                    logger.debug("Cleaned up failed module dir: $generatedModuleDir")
                }
                val error = "Failed to process system-only automatic module ${module.name}: ${e.message}"
                errors.add(error)
                feedback?.invoke("❌ $error")
                logger.error(error, e)
            }
        }

        feedback?.invoke("✅ Processed ${modules.count { it.automatic && it.depends.all { isSystemModule(it.toString()) } }} system-only automatic modules")
        logger.debug("Processed system-only automatic modules")
        return errors.toNonEmptyListOrNull() ?.let { it.left() } ?: Unit.right()
    }

    private fun migrateUnnamed(
        modules: MutableList<ZpmModuleKt>,
        delegate: ZpmModuleKt,
        feedback: ((String) -> Unit)?
    ): Either<NonEmptyList<String>, Collection<Path>> {
        val migratedPaths = mutableSetOf<Path>()
        val errors = mutableListOf<String>()
        feedback?.invoke("📝 Migrating unnamed modules to delegate")
        logger.debug("Migrating unnamed modules: ${modules.map { it.name ?: it.id }}")
        modules.filter { it.name == null }.forEach { module ->
            module.paths.forEach { path ->
                if (delegate.paths.add(path)) {
                    migratedPaths.add(path)
                    logger.debug("Migrated path $path to delegate for module ${module.id}")
                } else {
                    val error = "Duplicate path in delegate: $path"
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error)
                }
            }
            module.delegating = true
        }
        modules.removeIf { it.name == null }
        feedback?.invoke("✅ Migrated ${migratedPaths.size} paths from unnamed modules")
        return errors.toNonEmptyListOrNull()?.let { it.left() } ?: migratedPaths.right()
    }

    private fun delegateAutomatic(
        modules: MutableList<ZpmModuleKt>,
        delegate: ZpmModuleKt,
        feedback: ((String) -> Unit)?
    ): Either<ZpmResolutionErrorKt, Unit> {
        val errors = mutableListOf<String>()

        feedback?.invoke("🔄 Starting delegation of automatic modules (non-system)")
        logger.debug("Delegating automatics: scanning ${modules.size} modules")

        modules.filter { it.automatic && !isSystemModule(it.name ?: "") }
            .forEach { module ->
                try {
                    feedback?.invoke("➡️ Delegating automatic module: ${module.name ?: module.id}")
                    logger.debug("Delegating module ${module.name ?: module.id}")

                    // Move its paths into the delegate
                    module.paths.forEach { path ->
                        feedback?.invoke("   📦 Merging ${path.fileName} into delegate jar")
                        delegate.paths.add(path)
                    }

                    // Clear out the module’s own content
                    module.paths.clear()
                    module.delegating = true

                    feedback?.invoke("   ✅ ${module.name ?: module.id} marked as delegated (stub will be generated)")
                    logger.debug("Module ${module.name ?: module.id} delegated successfully")

                } catch (ex: Exception) {
                    val error = "❌ Failed to delegate module ${module.name ?: module.id}: ${ex.message}"
                    errors.add(error)
                    feedback?.invoke(error)
                    logger.error(error, ex)
                }
            }

        return if (errors.isNotEmpty()) {
            ZpmResolutionErrorKt.DependencyResolutionError(
                "Errors occurred during delegation:\n${errors.joinToString("\n")}"
            ).left()
        } else {
            feedback?.invoke("✅ Delegation phase complete. Total delegated modules: ${
                modules.count { it.delegating && it.automatic }
            }")
            Unit.right()
        }
    }

    private fun delegateModule(
        delegate: ZpmModuleKt,
        module: ZpmModuleKt,
        resolve: (String) -> ZpmModuleKt?,
        errors: MutableList<String>,
        delegatedPaths: MutableSet<Path> = mutableSetOf<Path>()
    ) {
        module.delegating = true
        val pathsToDelegate = module.paths.toSet()
        pathsToDelegate.forEach { path ->
            if (delegate.paths.add(path)) {
                delegatedPaths.add(path)
                logger.debug("Moved path $path to delegate for module ${module.name}")
                println("Moved path $path to delegate for module ${module.name}")
            } else {
                val error = "Duplicate path in delegate for module ${module.name}: $path"
                errors.add(error)
                feedback?.invoke("❌ $error")
                logger.error(error)
            }
        }
        module.paths.clear()
        module.depends.forEach { dep ->
            val modName = if (dep.moduleName.isNullOrEmpty()) "" else dep.moduleName
            val depModule = resolve(modName)
            println("The dependency module exists, will check for artifact paths")
            println("The dependency module for depModule: ${depModule?.name} will be shown below")
            println("The dependency paths for depModule: ${depModule?.name}, are in the list here: ${depModule?.paths}")

            if (depModule == null) {
                val error = "Unresolved dependency $dep for module ${module.name}"
                if (ignoreMissingDependencies) {
                    feedback?.invoke("⚠️ $error, skipping due to ignoreMissingDependencies")
                    logger.warn(error)
                } else {
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error)
                }
            } else if (!depModule.delegating && !depModule.name.isNullOrBlank()) {
                delegateModule(delegate, depModule, resolve, errors, delegatedPaths)
            }
        }
    }

    private fun generateDelegating(
        modules: Collection<ZpmModuleKt>,
        feedback: ((String) -> Unit)?
    ): Either<NonEmptyList<String>, Unit> {
        val errors = mutableListOf<String>()
        feedback?.invoke("📝 Generating stub JARs for delegating modules")
        logger.debug("Generating stub JARs for modules: ${modules.map { it.name ?: it.id }}")
        val javac = ToolProvider.findFirst("javac").orElseThrow { IllegalStateException("javac not found") }
        val generatedModulesDir = generatedDir.resolve("modules").createDirectories()

        modules.filter { it.delegating && it.automatic && it.name != null }.forEach { module ->
            if (dryRun) {
                feedback?.invoke("🧪 [dry-run] Would generate stub JAR for ${module.name}")
                logger.debug("[dry-run] Would generate stub JAR for ${module.name}")
                return@forEach
            }

            try {
                val generatedModuleDir = generatedModulesDir.resolve(module.name!!).createDirectories()
                val moduleInfoPath = moduleInfoGenerator.createDelegatedStubs(module, generatedModulesDir).getOrElse { ex ->
                    val error = "Failed to generate delegate module-info for ${module.name}: ${ex.message}"
                    if (ignoreMissingDependencies) {
                        feedback?.invoke("⚠️ $error, skipping due to ignoreMissingDependencies")
                        logger.warn(error)
                        return@forEach
                    } else {
                        errors.add(error)
                        feedback?.invoke("❌ $error")
                        logger.error(error, ex)
                        return@forEach
                    }
                }
                logger.debug("Generated delegate module-info at $moduleInfoPath")

                val javacArgs = mutableListOf<String>()
                if (atLeastVersion(javac, 21)) javacArgs.add("-proc:none")
                javacArgs.addAll(listOf("--module-path", modulesDir.toString(), "-d", generatedModuleDir.toString(), moduleInfoPath.toString()))
                feedback?.invoke("📜 javac command for ${module.name}: ${javacArgs.joinToString(" ")}")
                logger.debug("javac command: ${javacArgs.joinToString(" ")}")
                val javacOut = ByteArrayOutputStream()
                val javacErr = ByteArrayOutputStream()
                val exitCode = javac.run(PrintStream(javacOut), PrintStream(javacErr), *javacArgs.toTypedArray())
                val javacErrStr = javacErr.toString(Charsets.UTF_8)
                if (exitCode != 0) {
                    val error = "javac failed for ${module.name}: $javacErrStr"
                    if (ignoreMissingDependencies) {
                        feedback?.invoke("⚠️ $error, skipping due to ignoreMissingDependencies")
                        logger.warn(error)
                        return@forEach
                    } else {
                        errors.add(error)
                        feedback?.invoke("❌ $error")
                        logger.error(error)
                        return@forEach
                    }
                }
                feedback?.invoke("✅ Compiled delegate module-info for ${module.name}")

                val moduleInfoClass = generatedModuleDir.resolve("module-info.class")
                val multiReleaseModuleInfo = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
                val (realModuleInfo, entryName) = when {
                    moduleInfoClass.exists() -> moduleInfoClass to "module-info.class"
                    multiReleaseModuleInfo.exists() -> multiReleaseModuleInfo to "META-INF/versions/9/module-info.class"
                    else -> {
                        val error = "module-info.class not found for ${module.name}"
                        if (ignoreMissingDependencies) {
                            feedback?.invoke("⚠️ $error, skipping due to ignoreMissingDependencies")
                            logger.warn(error)
                            return@forEach
                        } else {
                            errors.add(error)
                            feedback?.invoke("❌ $error")
                            logger.error(error)
                            return@forEach
                        }
                    }
                }

                val modulePath = modulesDir.resolve("${module.name}.jar")
                createEmptyJar(modulePath).getOrElse { ex ->
                    val error = "Failed to create stub JAR for ${module.name}: ${ex.message}"
                    if (ignoreMissingDependencies) {
                        feedback?.invoke("⚠️ $error, skipping due to ignoreMissingDependencies")
                        logger.warn(error)
                        return@forEach
                    } else {
                        errors.add(error)
                        feedback?.invoke("❌ $error")
                        logger.error(error, ex)
                        return@forEach
                    }
                }
                jarExtender(modulePath, modulePath, JarEntry(entryName).apply { time = 318240000000L }, realModuleInfo).getOrElse { ex ->
                    val error = "Failed to extend stub JAR with module-info for ${module.name}: ${ex.message}"
                    if (ignoreMissingDependencies) {
                        feedback?.invoke("⚠️ $error, skipping due to ignoreMissingDependencies")
                        logger.warn(error)
                        return@forEach
                    } else {
                        errors.add(error)
                        feedback?.invoke("❌ $error")
                        logger.error(error, ex)
                        return@forEach
                    }
                }
                feedback?.invoke("✅ Generated stub JAR for ${module.name} at $modulePath")
                logger.debug("Generated stub JAR for ${module.name} at $modulePath")
            } catch (e: Exception) {
                val error = "Failed to process delegating module ${module.name}: ${e.message}"
                if (ignoreMissingDependencies) {
                    feedback?.invoke("⚠️ $error, skipping due to ignoreMissingDependencies")
                    logger.warn(error)
                } else {
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error, e)
                }
            }
        }

        feedback?.invoke("✅ Processed ${modules.count { it.delegating && it.automatic && it.name != null }} delegating modules")
        return if (errors.isNotEmpty() && !ignoreMissingDependencies) {
            errors.toNonEmptyListOrNull()!!.left()
        } else {
            Unit.right()
        }
    }

    private fun copyNonDelegating(
        modules: Collection<ZpmModuleKt>,
        feedback: ((String) -> Unit)?
    ): Either<NonEmptyList<String>, Unit> {
        val errors = mutableListOf<String>()
        feedback?.invoke("📝 Starting copy of non-delegating, non-automatic modules to $modulesDir")
        logger.debug("Preparing to copy modules: ${modules.map { it.name ?: it.id }}")

        modules.filter { !it.delegating && !it.automatic }.forEach { module ->
            if (module.name == null) {
                val error = "Unnamed module ${module.id} should have been migrated/delegated instead of copied"
                errors.add(error)
                feedback?.invoke("❌ $error")
                logger.error(error)
                return@forEach
            }
            feedback?.invoke("📦 Copying module: ${module.name} from paths ${module.paths}")
            module.paths.forEach { path ->
                if (!path.exists()) {
                    val error = "Artifact path does not exist for ${module.name}: $path"
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error)
                    return@forEach
                }
                val modulePath = modulesDir.resolve("${module.name}.jar")
                feedback?.invoke("🔗 Copying ${module.name} → $modulePath")
                jarCopier.copyJars(listOf(path), modulePath).getOrElse { ex ->
                    val error = "Failed to copy JAR for ${module.name}: ${ex.message}"
                    errors.add(error)
                    feedback?.invoke("❌ $error")
                    logger.error(error, ex)
                    return@forEach
                }
                feedback?.invoke("✅ Successfully copied ${module.name} to $modulePath")
                logger.debug("Copied ${module.name} to $modulePath")
            }
        }

        val copiedCount = modules.count { !it.delegating && !it.automatic }
        feedback?.invoke("✅ Completed copying $copiedCount non-delegating modules")
        return errors.toNonEmptyListOrNull()?.let { it.left() } ?: Unit.right()
    }


    @OptIn(ExperimentalPathApi::class)
    private fun processModules(
        delegate: ZpmModuleKt,
        modules: Collection<ZpmModuleKt>,
        feedback: ((String) -> Unit)?
    ): Either<ZpmResolutionErrorKt, Path> {
        feedback?.invoke("📦 Processing final module set before jlink")
        logger.debug("Processing modules: ${modules.map { it.name ?: it.id }}")

        // Check for unhandled automatic modules, unless ignoring missing dependencies
        val strayAutomatics = modules.filter { it.automatic && !it.delegating }
        if (strayAutomatics.isNotEmpty() && !ignoreMissingDependencies) {
            val error = "Automatic modules remain unhandled: ${
                strayAutomatics.joinToString { it.name ?: it.id.toString() }
            }"
            feedback?.invoke("❌ $error → every automatic module must be promoted or delegated before jlink")
            strayAutomatics.forEach { m ->
                feedback?.invoke("   ⚠️ Stray automatic: ${m.name ?: m.id}, paths=${m.paths}")
            }
            logger.error(error)
            return ZpmResolutionErrorKt.DependencyResolutionError(error).left()
        }

        return ensureDirectoryWritable(modulesDir, "modules directory").flatMap {
            ensureDirectoryWritable(generatedDir, "generated directory").flatMap {
                feedback?.invoke("✅ Ensured writable directories: $modulesDir, $generatedDir")

                // Copy non-delegating, non-automatic modules
                val nonDelegating = modules.filter { !it.delegating && !it.automatic }
                feedback?.invoke("📄 Preparing to copy ${nonDelegating.size} non-delegating modules")
                copyNonDelegating(nonDelegating, feedback).mapLeft {
                    val error = "Failed while copying non-delegating modules"
                    feedback?.invoke("❌ $error")
                    logger.error(error)
                    return@flatMap ZpmResolutionErrorKt.DependencyResolutionError(error).left()
                }

                // Build delegate module if it has paths
                val delegateJar = modulesDir.resolve("${ZpmModuleKt.DELEGATE_NAME}.jar")
                if (delegate.paths.isNotEmpty()) {
                    feedback?.invoke("📦 Building delegate module ${ZpmModuleKt.DELEGATE_NAME}")
                    return@flatMap moduleInfoGenerator.buildDelegateModule(
                        delegate,
                        generatedDir,
                        modulesDir, // Pass modulesDir explicitly
                        ignoreMissingDependencies
                    ).flatMap { jarPath ->
                        feedback?.invoke("✅ Built delegate JAR for jlink: $jarPath")
                        logger.debug("Using delegate JAR: $jarPath")
                        jarPath.right()
                    }
                }

                // Fallback to a non-delegating module or default JAR
                val defaultJar = if (nonDelegating.isNotEmpty()) {
                    val firstNonDelegating = nonDelegating.first()
                    val jarPath = modulesDir.resolve("${firstNonDelegating.name}.jar")
                    if (!jarPath.exists()) {
                        val error = "Non-delegating JAR not found: $jarPath"
                        feedback?.invoke("❌ $error")
                        logger.error(error)
                        return@flatMap ZpmResolutionErrorKt.DependencyResolutionError(error).left()
                    }
                    jarPath
                } else {
                    modulesDir.resolve("zilla.default.jar")
                }

                if (dryRun) {
                    feedback?.invoke("🧪 [dry-run] Would select default JAR: $defaultJar")
                    return@flatMap defaultJar.right()
                }

                if (!defaultJar.exists()) {
                    feedback?.invoke("🛠 No delegate or non-delegating JAR found, creating placeholder: $defaultJar")
                    createEmptyJar(defaultJar).getOrElse { ex ->
                        val error = "Failed to create placeholder JAR: ${ex.message}"
                        feedback?.invoke("❌ $error")
                        logger.error(error, ex)
                        return@flatMap ZpmResolutionErrorKt.DependencyResolutionError(error).left()
                    }
                    feedback?.invoke("✅ Created placeholder JAR: $defaultJar")
                }

                feedback?.invoke("✅ Using final JAR for jlink: $defaultJar")
                logger.debug("Using final JAR: $defaultJar")
                defaultJar.right()
            }
        }
    }

    private fun linkImageAndWriteLauncher(
        targetJar: Path,
        feedback: ((String) -> Unit)?
    ): Either<ZpmResolutionErrorKt, Unit> =
        either {
            feedback?.invoke("🔗 Linking image for $targetJar")

            imageLinker.link(targetJar, installDir).bind()
            feedback?.invoke("✅ Linked image to ${installDir.resolve("image")}")

            feedback?.invoke("📝 Writing launcher")

            launcherWriter.write("io.aklivity.zilla.runtime.command", installDir).bind()
            feedback?.invoke("✅ Wrote launcher to ${installDir.resolve("zilla.bat")}")
        }.mapLeft {
            it.printStackTrace()
            feedback?.invoke("❌ Failed to link image or write launcher: ${it.message}")
            ZpmResolutionErrorKt.DependencyResolutionError(
                "Failed to link image or write launcher: ${it.message}"
            )
        } as Either<ZpmResolutionErrorKt, Unit>

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

    open fun parseTemplate(templatePath: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, ZpmTemplate> {
        feedback?.invoke("📜 Parsing template: $templatePath")
        return Either.catch {
            json.decodeFromString<ZpmTemplate>(Files.readString(templatePath))
        }.mapLeft {
            feedback?.invoke("❌ Failed to parse template: ${it.message}")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to parse template: ${it.message}")
        }
    }

    fun installFromTemplate(templatePath: Path, feedback: ((String) -> Unit)? = null): Either<Any, Unit> {
        return checkTemplate(templatePath, feedback)
            .flatMap { parseTemplate(templatePath, feedback) }
            .flatMap { template -> resolveDependencies(template, feedback) }
            .flatMap { artifacts ->
                val modules = discoverModules(artifacts, feedback).toMutableList()
                val delegate = modules.find { it.name == ZpmModuleKt.DELEGATE_NAME } ?: ZpmModuleKt()

                migrateUnnamed(modules, delegate, feedback).flatMap { _ ->
                    generateSystemOnlyAutomatic(modules, feedback).flatMap {
                        delegateAutomatic(modules, delegate, feedback).flatMap {
                            // 🔑 FIX: generate stubs for *all* delegated automatics + delegate
                            val delegatedModules = modules.filter { it.delegating && it.automatic && it.name != null } + delegate
                            generateDelegating(delegatedModules, feedback).flatMap {
                                processModules(delegate, modules, feedback).flatMap { targetJar ->
                                    linkImageAndWriteLauncher(targetJar, feedback)
                                }
                            }
                        }
                    }
                }.map { Unit }
            }
    }

    private fun checkTemplate(templatePath: Path, feedback: ((String) -> Unit)?): Either<ZpmResolutionErrorKt, Unit> {
        return Either.catch {
            if (!Files.exists(templatePath)) throw IOException("Template file not found: $templatePath")
            if (!Files.isReadable(templatePath)) throw IOException("Template file not readable: $templatePath")
        }.mapLeft {
            feedback?.invoke("❌ Failed to check template: ${it.message}")
            ZpmResolutionErrorKt.DependencyResolutionError("Failed to check template: ${it.message}")
        }.map { Unit }
    }
    val knownNonSystemButConfusing = setOf("java.inject")
    private fun isSystemModule(moduleName: String): Boolean {
        return moduleName.startsWith("java.") || moduleName.startsWith("jdk.")
                && !knownNonSystemButConfusing.contains(moduleName)
    }

    private fun atLeastVersion(tool: ToolProvider, major: Int): Boolean {
        logger.debug("Checking if tool ${tool.name()} version >= $major")
        val out = ByteArrayOutputStream()
        tool.run(PrintStream(out), System.err, "--version")
        val matcher = Regex("""(\d+)\.""").find(out.toString(Charsets.UTF_8))
        val version = matcher?.groups?.get(1)?.value?.toIntOrNull() ?: 0
        val atLeast = version >= major
        logger.debug("Tool version: $version, at least $major: $atLeast")
        return atLeast
    }

    private fun jarExtender(
        sourcePath: Path,
        targetPath: Path,
        entry: JarEntry,
        moduleInfoPath: Path
    ): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        JarOutputStream(Files.newOutputStream(targetPath)).use { jos ->
            JarFile(sourcePath.toFile()).use { jar ->
                jar.entries().asSequence().forEach { je ->
                    if (je.name != entry.name) {
                        jos.putNextEntry(JarEntry(je.name).apply { time = je.time })
                        jar.getInputStream(je).use { it.copyTo(jos) }
                        jos.closeEntry()
                    }
                }
                jos.putNextEntry(entry)
                Files.copy(moduleInfoPath, jos)
                jos.closeEntry()
            }
        }
    }.mapLeft { ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Failed to extend JAR") }

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
}