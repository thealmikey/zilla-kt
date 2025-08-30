///*
// * Copyright 2021-2024 Aklivity Inc.
// *
// * Aklivity licenses this file to you under the Apache License,
// * version 2.0 (the "License"); you may not use this file except in compliance
// * with the License. You may obtain a copy of the License at:
// *
// *   http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
// * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
// * License for the specific language governing permissions and limitations
// * under the License.
// */
//package io.aklivity.zilla.manager.internal.commands.install
//
//import com.github.rvesse.airline.annotations.Command
//import com.github.rvesse.airline.annotations.Option
//import io.aklivity.zilla.manager.internal.ZpmCommand
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifact
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactId
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmCache
//import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmModule
//import jakarta.json.bind.JsonbBuilder
//import jakarta.json.bind.JsonbConfig
//import org.apache.maven.settings.Settings
//import org.apache.maven.settings.building.DefaultSettingsBuilder
//import org.apache.maven.settings.building.DefaultSettingsBuildingRequest
//import org.apache.maven.settings.io.DefaultSettingsReader
//import org.apache.maven.settings.io.DefaultSettingsWriter
//import org.apache.maven.settings.io.SettingsReader
//import org.apache.maven.settings.io.SettingsWriter
//import org.apache.maven.settings.validation.DefaultSettingsValidator
//import org.apache.maven.settings.validation.SettingsValidator
//import org.codehaus.plexus.logging.console.ConsoleLogger
//import org.eclipse.aether.repository.RemoteRepository
//import org.eclipse.aether.util.repository.AuthenticationBuilder
//import java.io.*
//import java.lang.String
//import java.lang.module.ModuleDescriptor
//import java.lang.module.ModuleFinder
//import java.lang.module.ModuleReference
//import java.net.URI
//import java.net.URISyntaxException
//import java.nio.charset.StandardCharsets
//import java.nio.file.*
//import java.util.*
//import java.util.function.*
//import java.util.function.Function
//import java.util.jar.JarEntry
//import java.util.jar.JarFile
//import java.util.jar.JarOutputStream
//import java.util.regex.Matcher
//import java.util.regex.Pattern
//import java.util.spi.ToolProvider
//import java.util.stream.Collectors
//import java.util.stream.Stream
//import java.util.zip.ZipException
//import java.util.zip.ZipFile
//import kotlin.Boolean
//import kotlin.Comparator
//import kotlin.Exception
//import kotlin.IllegalStateException
//import kotlin.Int
//import kotlin.RuntimeException
//import kotlin.Throws
//import kotlin.arrayOf
//import kotlin.assert
//import kotlin.collections.ArrayList
//import kotlin.collections.HashMap
//import kotlin.collections.HashSet
//import kotlin.collections.LinkedHashMap
//import kotlin.collections.LinkedHashSet
//import kotlin.collections.MutableCollection
//import kotlin.collections.MutableList
//import kotlin.collections.MutableMap
//import kotlin.collections.MutableSet
//import kotlin.collections.dropLastWhile
//import kotlin.collections.mutableListOf
//import kotlin.collections.toTypedArray
//import kotlin.text.format
//import kotlin.text.isEmpty
//import kotlin.text.replace
//import kotlin.text.split
//import kotlin.text.startsWith
//import kotlin.text.toByteArray
//import kotlin.text.toInt
//import kotlin.text.toRegex
//
//@Command(name = "install", description = "Install dependencies")
//class ThisZpmInstall : ZpmCommand() {
//    @Option(name = ["--verbose"], description = "Enable verbose logging")
//    var verbose: Boolean = false
//
//    @Option(name = ["--debug"], description = "Link jdk.jdwp.agent module")
//    var debug: Boolean = false
//
//    @Option(name = ["--instrument"], description = "Link java.instrument module", hidden = true)
//    var instrument: Boolean = false
//
//    @Option(name = ["--exclude-local-repository"], description = "Exclude the local Maven repository")
//    var excludeLocalRepo: Boolean = false
//
//    @Option(name = ["--exclude-remote-repositories"], description = "Exclude remote Maven repositories")
//    var excludeRemoteRepos: Boolean = false
//
//    @Option(name = ["--ignore-missing-dependencies"], hidden = true)
//    var ignoreMissingDependencies: Boolean = true
//
//    public override fun invoke() {
//        val level =
//            if (silent) ConsoleLogger.LEVEL_WARN else if (verbose) ConsoleLogger.LEVEL_DEBUG else ConsoleLogger.LEVEL_INFO
//        val logger = ConsoleLogger(level, "ZpmInstall")
//
//        try {
//            var config: ZpmConfiguration
//
//            val zpmFile = configDir.resolve("zpm.json")
//
//            logger.info(String.format("reading %s", zpmFile))
//            logger.info(String.format("reading %s", zpmFile))
//            config = readOrDefaultConfig(zpmFile)
//
//            val lockFile = lockDir.resolve("zpm-lock.json")
//            logger.info(String.format("reading %s", lockFile))
//            config = overrideConfigIfLocked(config, zpmFile, lockFile)
//
//            logger.info("resolving dependencies")
//            Files.createDirectories(cacheDir)
//            val repositories: MutableList<ZpmRepository> = ArrayList<ZpmRepository>(config.repositories)
//
//            val home = System.getProperty("user.home")
//            if (!excludeLocalRepo) {
//                val localRepo = String.format("file://%s/.m2/repository", home)
//                repositories.add(0, ZpmRepository(localRepo))
//            }
//
//            if (excludeRemoteRepos) {
//                repositories.removeIf { r: ZpmRepository? -> !r!!.location.startsWith("file:") }
//            }
//
//            val settingsFile = File(String.format("/%s/.m2/settings.xml", home))
//
//            val settingsReader: SettingsReader = DefaultSettingsReader()
//            val settingsWriter: SettingsWriter = DefaultSettingsWriter()
//            val settingsValidator: SettingsValidator = DefaultSettingsValidator()
//
//            val settingsBuilder = DefaultSettingsBuilder(
//                settingsReader, settingsWriter, settingsValidator
//            )
//            val request = DefaultSettingsBuildingRequest()
//            request.setGlobalSettingsFile(settingsFile)
//            request.setUserSettingsFile(settingsFile)
//
//            val result = settingsBuilder.build(request)
//            val settings = result.getEffectiveSettings()
//
//            val remoteRepositories = asRemoteRepositories(settings, repositories)
//
//            val cache = ZpmCache(remoteRepositories, cacheDir, logger)
//            val artifacts: MutableCollection<ZpmArtifact> = cache.resolve(config.imports, config.dependencies)
//
//            val resolvables = artifacts.stream()
//                .map<ZpmArtifactId?> { a: ZpmArtifact? -> a!!.id }
//                .collect(
//                    Collectors.toMap(
//                        Function { id: ZpmArtifactId? -> ZpmDependency.of(id!!.group, id.artifact, null) },
//                        Function { id: ZpmArtifactId? -> ZpmDependency.of(id!!.group, id.artifact, id.version) },
//                        BinaryOperator { first: ZpmDependency?, second: ZpmDependency? -> second })
//                )
//
//            val resolved = ZpmConfiguration()
//            resolved.repositories = config.repositories
//            resolved.imports = null
//            resolved.dependencies = config.dependencies.stream()
//                .map<ZpmDependency?> { d: ZpmDependency? ->
//                    Optional.ofNullable<ZpmDependency?>(resolvables.get(d)).orElse(d)
//                }
//                .collect(Collectors.toList())
//
//            if (resolved != config) {
//                logger.info(String.format("writing %s", lockFile))
//                writeLockFile(resolved, lockFile)
//            }
//
//            Files.createDirectories(modulesDir)
//            logger.info(String.format("MIKE: AFTER CREATING DIRECTORIES FOR MODULES"))
//
//            Files.createDirectories(generatedDir)
//            logger.info(String.format("MIKE: AFTER CREATING DIRECTORIES FOR GENERATED"))
//
//            val delegate = ZpmModule()
//            val modules = discoverModules(artifacts)
//            logger.info(String.format("MIKE discovered %d modules", modules.size))
//            migrateUnnamed(modules, delegate)
//            logger.info(String.format("MIKE migrated %d unnamed modules to delegate", delegate.paths.size))
//            generateSystemOnlyAutomatic(logger, modules)
//            logger.info(String.format("MIKE generated system-only automatic modules"))
//            delegateAutomatic(modules, delegate)
//            logger.info(String.format("MIKE delegated %d automatic modules", delegate.paths.size))
//            copyNonDelegating(modules)
//            logger.info(String.format("MIKE copied %d non-delegating modules", modules.size - delegate.paths.size))
//
//            if (!delegate.paths.isEmpty()) {
//                logger.info(String.format("MIKE generating delegate module %s", delegate.name))
//                generateDelegate(logger, delegate)
//                logger.info(String.format("MIKE generated delegate module %s", delegate.name))
//                logger.info(String.format("MIKE generating delegating modules"))
//                generateDelegating(modules)
//                logger.info(
//                    String.format(
//                        "MIKE generated %d delegating modules",
//                        modules.stream().filter { m: ZpmModule? -> m!!.delegating }.count()
//                    )
//                )
//            }
//
//            deleteDirectories(imageDir)
//            logger.info("deleted image directory")
//            logger.info(String.format("MIKE: AFTER DELETING IMAGE DIRECTORY %s", imageDir))
//
//            logger.info("linking modules")
//            linkModules(modules)
//            logger.info("linked modules")
//
//            generateLauncher()
//            logger.info("generated launcher")
//        } catch (ex: Exception) {
//            logger.error(String.format("MIKE CHECK ERROR HERE 😁 Error: %s", ex.message))
//            throw RuntimeException(ex)
//        }
//    }
//
//    @Throws(URISyntaxException::class)
//    private fun asRemoteRepositories(
//        settings: Settings,
//        repositories: MutableList<ZpmRepository>
//    ): MutableList<RemoteRepository?> {
//        val remoteRepositories: MutableList<RemoteRepository?> = ArrayList<RemoteRepository?>()
//
//        for (repository in repositories) {
//            val host = URI(repository.location).getHost()
//            val repoBuilder =
//                RemoteRepository.Builder(host, "default", repository.location)
//                    .setRepositoryManager(true)
//                    .setId(host)
//
//            val server = settings.getServer(host)
//            if (server != null) {
//                val authenticationBuilder = AuthenticationBuilder()
//                    .addUsername(server.getUsername())
//                    .addPassword(server.getPassword())
//                repoBuilder.setAuthentication(authenticationBuilder.build())
//            }
//            remoteRepositories.add(repoBuilder.build())
//        }
//        return remoteRepositories
//    }
//
//    @Throws(IOException::class)
//    private fun readOrDefaultConfig(
//        zpmFile: Path
//    ): ZpmConfiguration {
//        var config = ZpmConfiguration()
//        config.repositories = mutableListOf<ZpmRepository?>()
//        config.imports = mutableListOf<ZpmDependency?>()
//        config.dependencies = mutableListOf<ZpmDependency?>()
//
//        val builder = JsonbBuilder.newBuilder()
//            .withConfig(JsonbConfig().withFormatting(true))
//            .build()
//
//        if (Files.exists(zpmFile)) {
//            Files.newInputStream(zpmFile).use { `in` ->
//                config = builder.fromJson<ZpmConfiguration?>(`in`, ZpmConfiguration::class.java)
//            }
//        }
//
//        return config
//    }
//
//    @Throws(IOException::class)
//    private fun overrideConfigIfLocked(
//        config: ZpmConfiguration,
//        zpmFile: Path,
//        lockFile: Path
//    ): ZpmConfiguration {
//        var config = config
//        if (Files.exists(lockFile) &&
//            Files.getLastModifiedTime(lockFile).compareTo(Files.getLastModifiedTime(zpmFile)) >= 0
//        ) {
//            val builder = JsonbBuilder.newBuilder()
//                .withConfig(JsonbConfig().withFormatting(true))
//                .build()
//
//            Files.newInputStream(lockFile).use { `in` ->
//                config = builder.fromJson<ZpmConfiguration?>(`in`, ZpmConfiguration::class.java)
//            }
//        }
//        return config
//    }
//
//    @Throws(IOException::class)
//    private fun writeLockFile(
//        config: ZpmConfiguration?,
//        lockFile: Path
//    ) {
//        val builder = JsonbBuilder.newBuilder()
//            .withConfig(JsonbConfig().withFormatting(true))
//            .build()
//
//        Files.createDirectories(lockDir)
//        Files.newOutputStream(lockFile).use { out ->
//            builder.toJson(config, out)
//        }
//    }
//
//    private fun discoverModules(
//        artifacts: MutableCollection<ZpmArtifact>
//    ): MutableCollection<ZpmModule> {
//        val artifactPaths =
//            artifacts.stream().map<Path?> { a: ZpmArtifact? -> a!!.path }.toArray<Path?> { _Dummy_.__Array__() }
//        val references: MutableSet<ModuleReference?> = HashSet<ModuleReference?>()
//
//        for (path in artifactPaths) {
//            val finder = ModuleFinder.of(path)
//            references.addAll(finder.findAll())
//        }
//
//        val descriptors = references
//            .stream()
//            .filter { r: ModuleReference? -> r!!.location().isPresent() }
//            .collect(
//                Collectors.toMap(
//                    Function { r: ModuleReference? -> r!!.location().get() },
//                    Function { r: ModuleReference? -> r!!.descriptor() })
//            )
//
//        val modules: MutableCollection<ZpmModule> = LinkedHashSet<ZpmModule>()
//        for (artifact in artifacts) {
//            val artifactURI = artifact.path.toUri()
//            val descriptor = descriptors.get(artifactURI)
//            val module = if (descriptor != null) ZpmModule(descriptor, artifact) else ZpmModule(artifact)
//            modules.add(module)
//        }
//
//        return modules
//    }
//
//    private fun migrateUnnamed(
//        modules: MutableCollection<ZpmModule>,
//        delegate: ZpmModule
//    ) {
//        val iterator = modules.iterator()
//        while (iterator.hasNext()) {
//            val module = iterator.next()
//            if (module.name == null) {
//                delegate.paths.addAll(module.paths)
//                iterator.remove()
//            }
//        }
//
//        assert(!modules.stream().anyMatch { m: ZpmModule? -> m!!.name == null })
//    }
//
//    private fun delegateAutomatic(
//        modules: MutableCollection<ZpmModule>,
//        delegate: ZpmModule
//    ) {
//        val modulesMap: MutableMap<ZpmArtifactId?, ZpmModule?> = LinkedHashMap<ZpmArtifactId?, ZpmModule?>()
//        modules.forEach(Consumer { m: ZpmModule? -> modulesMap.put(m!!.id, m) })
//
//        for (module in modules) {
//            if (module.automatic) {
//                val resolved = modulesMap.get(module.id)
//                if (resolved != null) {
//                    delegateModule(delegate, module, Function { key: ZpmArtifactId? -> modulesMap.get(key)!! })
//                } else {
//                    System.err.printf("⚠️ Skipping automatic module without delegate: %s%n", module.name)
//                }
//            }
//        }
//
//        // No hard assertion: log remaining unresolved automatic modules
//        modules.stream()
//            .filter { m: ZpmModule? -> m!!.automatic && !m.delegating }
//            .forEach { m: ZpmModule? -> System.err.printf("⚠️ Unresolved automatic module: %s%n", m!!.name) }
//    }
//
//
//    private fun delegateModule(
//        delegate: ZpmModule,
//        module: ZpmModule,
//        lookup: Function<ZpmArtifactId?, ZpmModule>
//    ) {
//        if (!module.delegating) {
//            delegate.paths.addAll(module.paths)
//            module.paths.clear()
//            module.delegating = true
//
//            for (dependId in module.depends) {
//                val depend = lookup.apply(dependId)
//                delegateModule(delegate, depend, lookup)
//            }
//        }
//    }
//
//    @Throws(IOException::class)
//    private fun generateSystemOnlyAutomatic(
//        logger: ConsoleLogger,
//        modules: MutableCollection<ZpmModule>
//    ) {
//        logger.info("Generating system-only automatic modules")
//        logger.info(String.format("MIKE: Generating system-only automatic modules for %d modules", modules.size))
//        logger.info("➡️ ignoreMissingDependencies: " + ignoreMissingDependencies)
//
//        val promotions: MutableMap<ZpmModule?, Path?> = IdentityHashMap<ZpmModule?, Path?>()
//
//        for (module in modules) {
//            logger.debug(String.format("🟡 Checking module: %s", module.name))
//            val artifactPath = module.paths.iterator().next()
//
//            // Skip modular JARs (including multi-release)
//            try {
//                FileSystems.newFileSystem(artifactPath, null as ClassLoader?).use { fs ->
//                    val moduleInfo = fs.getPath("/module-info.class")
//                    val multiReleaseModuleInfo = fs.getPath("/META-INF/versions/9/module-info.class")
//                    if (Files.exists(moduleInfo) || Files.exists(multiReleaseModuleInfo)) {
//                        logger.debug(String.format("✅ Skipping already-modular JAR: %s", module.name))
//                        continue
//                    }
//                }
//            } catch (e: IOException) {
//                logger.error("❌ Failed to open JAR as FileSystem: " + artifactPath, e)
//                throw e
//            }
//
//            if (module.automatic && module.depends.isEmpty()) {
//                logger.info(
//                    String.format(
//                        "⚙️ Generating module info for system-only automatic module: %s",
//                        module.name
//                    )
//                )
//                val generatedModulesDir = generatedDir.resolve("modules")
//                val generatedModuleDir = generatedModulesDir.resolve(module.name)
//
//                deleteDirectories(generatedModuleDir)
//                Files.createDirectories(generatedModuleDir)
//
//                val jdeps = ToolProvider.findFirst("jdeps")
//                    .orElseThrow<IllegalStateException?>(Supplier { IllegalStateException("jdeps tool not found") })
//
//                val jdepsArgs: MutableList<String?> = ArrayList<String?>()
//                jdepsArgs.add("--generate-open-module")
//                if (ignoreMissingDependencies) {
//                    jdepsArgs.add("--ignore-missing-deps")
//                }
//                jdepsArgs.add(generatedModulesDir.toString())
//                jdepsArgs.add(artifactPath.toString())
//
//                logger.debug("jdeps args: " + String.join(" ", jdepsArgs))
//
//                val jdepsResult: Int
//                PrintStream(OutputStream.nullOutputStream()).use { nullOut ->
//                    jdepsResult = jdeps.run(
//                        nullOut, nullOut, *jdepsArgs.toArray<kotlin.String?>(
//                            IntFunction { _Dummy_.__Array__() })
//                    )
//                }
//                if (jdepsResult != 0) {
//                    logger.error(kotlin.String.format("❌ jdeps failed for %s\nOutput:\n", module.name))
//                    if (ignoreMissingDependencies) {
//                        logger.warn("⚠️ jdeps failed, skipping module: " + module.name)
//                        continue
//                    }
//                    throw IOException("jdeps failed for module: " + module.name)
//                }
//
//                val generatedModuleInfo: Path = generatedModuleDir.resolve(MODULE_INFO_JAVA_FILENAME)
//                if (!Files.exists(generatedModuleInfo)) {
//                    logger.warn("⚠️ Expected module-info.java not found for: " + module.name)
//                    continue
//                }
//
//                logger.info("✅ Generated module-info.java for: " + module.name)
//                expandJar(generatedModuleDir, artifactPath)
//
//                val javac = ToolProvider.findFirst("javac")
//                    .orElseThrow<IllegalStateException?>(Supplier { IllegalStateException("javac tool not found") })
//
//                val javacArgs: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
//                if (atLeastVersion(javac, 21)) {
//                    javacArgs.add("-proc:none")
//                }
//                javacArgs.add("-d")
//                javacArgs.add(generatedModuleDir.toString())
//                javacArgs.add(generatedModuleInfo.toString())
//
//                val javacResult: Int
//                PrintStream(OutputStream.nullOutputStream()).use { nullOut ->
//                    javacResult = javac.run(
//                        nullOut, nullOut, *javacArgs.toArray<kotlin.String?>(
//                            IntFunction { _Dummy_.__Array__() })
//                    )
//                }
//                if (javacResult != 0) {
//                    throw IOException("javac failed for: " + module.name)
//                }
//
//                val compiledModuleInfo = generatedModuleDir.resolve("module-info.class")
//                val compiledModuleInfoMR = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
//
//                val realCompiledModuleInfo: Path?
//                val realEntryName: kotlin.String?
//                if (Files.exists(compiledModuleInfo)) {
//                    realCompiledModuleInfo = compiledModuleInfo
//                    realEntryName = "module-info.class"
//                } else if (Files.exists(compiledModuleInfoMR)) {
//                    realCompiledModuleInfo = compiledModuleInfoMR
//                    realEntryName = "META-INF/versions/9/module-info.class"
//                } else {
//                    logger.error("❌ Expected compiled module-info.class not found in either location for: " + module.name)
//                    throw IOException("Expected compiled module-info.class not found in either location for: " + module.name)
//                }
//
//                val generatedModulePath = generatedModulesDir.resolve(module.name + ".jar")
//                val moduleInfoEntry = JarEntry(realEntryName)
//                moduleInfoEntry.setTime(318240000000L)
//                extendJar(artifactPath, generatedModulePath, moduleInfoEntry, realCompiledModuleInfo)
//                promotions.put(module, generatedModulePath)
//            }
//        }
//
//        for (entry in promotions.entries) {
//            val module: ZpmModule = entry.key!!
//            val newArtifactPath: Path = entry.value!!
//
//            val descriptor = moduleDescriptor(newArtifactPath)
//            if (descriptor == null) {
//                logger.error("❌ Failed to load module descriptor for: " + module.name)
//                continue
//            }
//
//            val newArtifact = ZpmArtifact(module.id, newArtifactPath, module.depends)
//            val promotion = ZpmModule(descriptor, newArtifact)
//
//            modules.remove(module)
//            modules.add(promotion)
//        }
//    }
//
//
//    private fun jarIsModular(jarPath: Path): Boolean {
//        try {
//            FileSystems.newFileSystem(jarPath, null as ClassLoader?).use { fs ->
//                val moduleInfo = fs.getPath("/module-info.class")
//                val multiReleaseModuleInfo = fs.getPath("/META-INF/versions/9/module-info.class")
//                return Files.exists(moduleInfo) || Files.exists(multiReleaseModuleInfo)
//            }
//        } catch (e: IOException) {
//            return false // treat unreadable JARs as non-modular
//        }
//    }
//
//
//    @Throws(IOException::class)
//    private fun copyNonDelegating(
//        modules: MutableCollection<ZpmModule>
//    ) {
//        for (module in modules) {
//            if (!module.delegating) {
//                assert(module.paths.size == 1)
//                val artifactPath = module.paths.iterator().next()
//                val modulePath = modulePath(module)
//                Files.copy(artifactPath, modulePath, StandardCopyOption.REPLACE_EXISTING)
//            }
//        }
//    }
//
//    @Throws(IOException::class)
//    private fun generateDelegate(
//        logger: ConsoleLogger, delegate: ZpmModule
//    ) {
//        val generatedModulesDir = generatedDir.resolve("modules")
//        val generatedDelegateDir = generatedModulesDir.resolve(delegate.name)
//        Files.createDirectories(generatedModulesDir)
//
//        val generatedDelegatePath = generatedModulesDir.resolve(kotlin.String.format("%s.jar", delegate.name))
//
//        JarOutputStream(Files.newOutputStream(generatedDelegatePath)).use { moduleJar ->
//            val manifestPath = Paths.get("META-INF", "MANIFEST.MF")
//            val servicesPath = Paths.get("META-INF", "services")
//            val packageInfoName = "package-info.class"
//            val excludedPackage = Paths.get("org", "eclipse", "yasson", "internal", "components")
//            val excludedClass = "BeanManagerInstanceCreator"
//
//            val entryNames: MutableSet<kotlin.String?> = HashSet<kotlin.String?>()
//            val services: MutableMap<kotlin.String?, MutableList<kotlin.String?>?> =
//                HashMap<kotlin.String?, MutableList<kotlin.String?>?>()
//
//            for (path in delegate.paths) {
//                JarFile(path.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { artifactJar ->
//                    for (entry in Collections.list<JarEntry?>(artifactJar.entries())) {
//                        val entryName = entry.getName()
//                        val entryPath = Paths.get(entryName)
//                        if (entryName == "module-info.class" ||
//                            entryName == "META-INF/versions/9/module-info.class" ||
//                            entryPath == manifestPath ||
//                            entryPath.endsWith(packageInfoName) ||
//                            (entryPath.startsWith(excludedPackage) &&
//                                    entryPath.getFileName().toString().startsWith(excludedClass))
//                        ) {
//                            continue
//                        }
//
//                        artifactJar.getInputStream(entry).use { input ->
//                            if (entryPath.startsWith(servicesPath) &&
//                                entryPath.getNameCount() - servicesPath.getNameCount() == 1
//                            ) {
//                                val servicePath = servicesPath.relativize(entryPath)
//                                assert(servicePath.getNameCount() == 1)
//                                val serviceName = servicePath.toString()
//                                val serviceImpl = String(input.readAllBytes(), StandardCharsets.UTF_8)
//                                services.computeIfAbsent(serviceName) { s: kotlin.String? -> ArrayList<kotlin.String?>() }!!
//                                    .addAll(
//                                        Arrays.asList<kotlin.String>(
//                                            *serviceImpl.split("\\R".toRegex()).dropLastWhile { it.isEmpty() }
//                                                .toTypedArray()))
//                            } else if (entryNames.add(entryName)) {
//                                moduleJar.putNextEntry(entry)
//                                moduleJar.write(input.readAllBytes())
//                                moduleJar.closeEntry()
//                            }
//                        }
//                    }
//                }
//            }
//            for (service in services.entries) {
//                val serviceName: kotlin.String = service.key!!
//                val servicePath = servicesPath.resolve(serviceName)
//                val serviceImpl = String.join("\n", service.value)
//
//                val newEntry = JarEntry(servicePath.toString())
//                newEntry.setTime(318240000000L)
//                moduleJar.putNextEntry(newEntry)
//                moduleJar.write(serviceImpl.toByteArray(StandardCharsets.UTF_8))
//                moduleJar.closeEntry()
//            }
//        }
//        // Step 2: Generate module-info.java using jdeps
//        var jdepsArgs = Arrays.asList<kotlin.String?>(
//            "--generate-module-info", generatedModulesDir.toString(),
//            generatedDelegatePath.toString()
//        )
//        if (ignoreMissingDependencies) {
//            jdepsArgs = LinkedList<kotlin.String?>(jdepsArgs)
//            jdepsArgs.add(0, "--ignore-missing-deps")
//        }
//
//        val jdeps = ToolProvider.findFirst("jdeps").get()
//        jdeps.run(System.out, System.err, *jdepsArgs.toArray<kotlin.String?>(IntFunction { _Dummy_.__Array__() }))
//
//        val generatedModuleInfo: Path = generatedDelegateDir.resolve(MODULE_INFO_JAVA_FILENAME)
//        if (!Files.exists(generatedModuleInfo)) {
//            throw IOException("Failed to generate module info for delegate module: " + delegate.name)
//        }
//
//        logger.info("Generated module info for delegate module")
//
//        // Patch module-info.java with `uses`
//        val moduleInfoContents = Files.readString(generatedModuleInfo)
//        val pattern = Pattern.compile("(?:provides\\s+)([^\\s]+)(?:\\s+with)")
//        val matcher = pattern.matcher(moduleInfoContents)
//        val uses: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
//        while (matcher.find()) {
//            val service = matcher.group(1)
//            uses.add(kotlin.String.format("uses %s;", service))
//        }
//
//        if (!uses.isEmpty()) {
//            Files.writeString(
//                generatedModuleInfo,
//                moduleInfoContents.replace("}", String.join("\n", uses) + "\n}")
//            )
//        }
//
//        expandJar(generatedDelegateDir, generatedDelegatePath)
//
//        val javac = ToolProvider.findFirst("javac").get()
//
//        val args: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
//        if (atLeastVersion(javac, 21)) {
//            args.add("-proc:none")
//        }
//
//        args.add("-d")
//        args.add(generatedDelegateDir.toString())
//        args.add(generatedModuleInfo.toString())
//
//        val result =
//            javac.run(System.out, System.err, *args.toArray<kotlin.String?>(IntFunction { _Dummy_.__Array__() }))
//        if (result != 0) {
//            throw IOException("javac failed to compile delegate module-info.java for: " + delegate.name)
//        }
//
//        // Step 3: Write final module-info.class into final delegate JAR
//        val compiledModuleInfoRoot = generatedDelegateDir.resolve("module-info.class")
//        val compiledModuleInfoMR = generatedDelegateDir.resolve("META-INF/versions/9/module-info.class")
//
//        val realCompiledModuleInfo: Path?
//        val realEntryName: kotlin.String?
//
//        if (Files.exists(compiledModuleInfoRoot)) {
//            realCompiledModuleInfo = compiledModuleInfoRoot
//            realEntryName = "module-info.class"
//        } else if (Files.exists(compiledModuleInfoMR)) {
//            realCompiledModuleInfo = compiledModuleInfoMR
//            realEntryName = "META-INF/versions/9/module-info.class"
//        } else {
//            throw IOException("Compiled module-info.class not found in either location for delegate: " + delegate.name)
//        }
//
//        val delegatePath = modulePath(delegate)
//        val moduleInfoEntry = JarEntry(realEntryName)
//        moduleInfoEntry.setTime(318240000000L)
//        extendJar(generatedDelegatePath, delegatePath, moduleInfoEntry, realCompiledModuleInfo)
//    }
//
//
//    @Throws(IOException::class)
//    private fun generateDelegating(
//        modules: MutableCollection<ZpmModule>
//    ) {
//        for (module in modules) {
//            if (module.delegating) {
//                val generatedModulesDir = generatedDir.resolve("modules")
//                val generatedModuleDir = generatedModulesDir.resolve(module.name)
//                Files.createDirectories(generatedModuleDir)
//
//                val generatedModuleInfo: Path = generatedModuleDir.resolve(MODULE_INFO_JAVA_FILENAME)
//                Files.write(
//                    generatedModuleInfo, Arrays.asList<kotlin.String?>(
//                        kotlin.String.format("open module %s {", module.name),
//                        kotlin.String.format("    requires transitive %s;", ZpmModule.DELEGATE_NAME),
//                        "}"
//                    )
//                )
//
//                val javac = ToolProvider.findFirst("javac").get()
//
//                val args: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
//                if (atLeastVersion(javac, 21)) {
//                    args.add("-proc:none")
//                }
//
//                args.add("-d")
//                args.add(generatedModuleDir.toString())
//
//                args.add("--module-path")
//                args.add(modulesDir.toString())
//
//                args.add(generatedModuleInfo.toString())
//
//                val result = javac.run(
//                    System.out,
//                    System.err,
//                    *args.toArray<kotlin.String?>(IntFunction { _Dummy_.__Array__() })
//                )
//                if (result != 0) {
//                    throw IOException("javac failed to compile module-info.java for delegating module: " + module.name)
//                }
//
//                // Try both root and multi-release output locations
//                val compiledModuleInfoRoot = generatedModuleDir.resolve("module-info.class")
//                val compiledModuleInfoMR = generatedModuleDir.resolve("META-INF/versions/9/module-info.class")
//
//                val realCompiledModuleInfo: Path?
//                val realEntryName: kotlin.String?
//
//                if (Files.exists(compiledModuleInfoRoot)) {
//                    realCompiledModuleInfo = compiledModuleInfoRoot
//                    realEntryName = "module-info.class"
//                } else if (Files.exists(compiledModuleInfoMR)) {
//                    realCompiledModuleInfo = compiledModuleInfoMR
//                    realEntryName = "META-INF/versions/9/module-info.class"
//                } else {
//                    throw IOException("Compiled module-info.class not found in either location for delegating module: " + module.name)
//                }
//
//                val modulePath = modulePath(module)
//                JarOutputStream(Files.newOutputStream(modulePath)).use { jar ->
//                    val newEntry = JarEntry(realEntryName)
//                    newEntry.setTime(318240000000L)
//                    jar.putNextEntry(newEntry)
//                    jar.write(Files.readAllBytes(realCompiledModuleInfo))
//                    jar.closeEntry()
//                }
//            }
//        }
//    }
//
//
//    @Throws(IOException::class)
//    private fun linkModules(
//        modules: MutableCollection<ZpmModule>
//    ) {
//        val jlink = ToolProvider.findFirst("jlink").get()
//
//        val compress = if (atLeastVersion(jlink, 21)) "zip-6" else "2"
//
//        val extraModuleNames: MutableList<kotlin.String?> = ArrayList<kotlin.String?>()
//        if (debug) {
//            extraModuleNames.add("jdk.jdwp.agent")
//        }
//        if (instrument) {
//            extraModuleNames.add("java.instrument")
//        }
//
//        extraModuleNames.add("java.management")
//        extraModuleNames.add("jdk.management")
//
//        val moduleNames =
//            Stream.concat<kotlin.String?>(
//                modules.stream().map<kotlin.String?> { m: ZpmModule? -> m!!.name },
//                extraModuleNames.stream()
//            )
//
//        val args: MutableList<kotlin.String?> = ArrayList<kotlin.String?>(
//            Arrays.asList<kotlin.String>(
//                "--module-path", modulesDir.toString(),
//                "--output", imageDir.toString(),
//                "--no-header-files",
//                "--no-man-pages",
//                "--compress", compress,
//                "--add-modules", moduleNames.collect(Collectors.joining(","))
//            )
//        )
//
//        args.add("--ignore-signing-information")
//
//        if (!debug) {
//            args.add("--strip-debug")
//        }
//
//        if (!silent) {
//            args.add("--verbose")
//        }
//
//        jlink.run(
//            System.out,
//            System.err,
//            *args.toArray<kotlin.String?>(IntFunction { _Dummy_.__Array__() })
//        )
//    }
//
//    @Throws(IOException::class)
//    private fun generateLauncher() {
//        val zillaPath = launcherDir.resolve("zilla")
//        Files.write(
//            zillaPath, Arrays.asList<kotlin.String?>(
//                "#!/bin/sh",
//                "if [ -n \"\$ZILLA_INCUBATOR_ENABLED\" ]; then",
//                "JAVA_OPTIONS=\"\$JAVA_OPTIONS -Dzilla.incubator.enabled=\$ZILLA_INCUBATOR_ENABLED\"",
//                "fi",
//                "ZILLA_DIRECTORY=\"\${0%/*}\"",
//                "JAVA_OPTIONS=\"\$JAVA_OPTIONS -Dzilla.directory=\$ZILLA_DIRECTORY\"",
//                kotlin.String.format(
//                    String.join(
//                        " ", mutableListOf<kotlin.String?>(
//                            "exec \$ZILLA_DIRECTORY/%s/bin/java",
//                            "\$JAVA_OPTIONS",
//                            "-m io.aklivity.zilla.runtime.command/io.aklivity.zilla.runtime.command.internal.ZillaMain \"$@\""
//                        )
//                    ),
//                    imageDir
//                )
//            )
//        )
//        zillaPath.toFile().setExecutable(true)
//    }
//
//    private fun moduleDescriptor(
//        archive: Path
//    ): ModuleDescriptor? {
//        var module: ModuleDescriptor? = null
//        val moduleRefs = ModuleFinder.of(archive).findAll()
//        if (!moduleRefs.isEmpty()) {
//            module = moduleRefs.iterator().next().descriptor()
//        }
//        return module
//    }
//
//    private fun modulePath(
//        module: ZpmModule
//    ): Path {
//        return modulesDir.resolve(kotlin.String.format("%s.jar", module.name))
//    }
//
//    @Throws(IOException::class)
//    private fun expandJar(
//        targetDir: Path,
//        sourcePath: Path
//    ) {
//        JarFile(sourcePath.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { sourceJar ->
//            for (entry in Collections.list<JarEntry?>(sourceJar.entries())) {
//                val entryPath = targetDir.resolve(entry.getName()).normalize()
//                if (!entryPath.startsWith(targetDir)) {
//                    throw IOException("Bad zip entry")
//                } else if (entry.isDirectory()) {
//                    Files.createDirectories(entryPath)
//                } else {
//                    val parentPath = entryPath.getParent()
//                    if (!Files.exists(parentPath)) {
//                        Files.createDirectories(parentPath)
//                    }
//
//                    sourceJar.getInputStream(entry).use { input ->
//                        Files.write(entryPath, input.readAllBytes())
//                    }
//                }
//            }
//        }
//    }
//
//    @Throws(IOException::class)
//    private fun extendJar(
//        sourcePath: Path,
//        targetPath: Path,
//        newEntry: JarEntry?,
//        newEntryPath: Path
//    ) {
//        if (!Files.exists(sourcePath) || Files.size(sourcePath) < 32) {
//            throw IOException("❌ Source JAR missing or too small: " + sourcePath)
//        }
//
//        try {
//            JarFile(sourcePath.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { sourceJar ->
//                JarOutputStream(
//                    Files.newOutputStream(targetPath)
//                ).use { targetJar ->
//                    for (entry in Collections.list<JarEntry?>(sourceJar.entries())) {
//                        targetJar.putNextEntry(entry)
//                        if (!entry.isDirectory()) {
//                            sourceJar.getInputStream(entry).use { input ->
//                                targetJar.write(input.readAllBytes())
//                            }
//                        }
//                        targetJar.closeEntry()
//                    }
//                    if (!Files.exists(newEntryPath)) {
//                        throw IOException("❌ New entry file not found: " + newEntryPath)
//                    }
//
//                    targetJar.putNextEntry(newEntry)
//                    targetJar.write(Files.readAllBytes(newEntryPath))
//                    targetJar.closeEntry()
//                }
//            }
//        } catch (ze: ZipException) {
//            throw IOException("❌ Failed to open source JAR (corrupt or invalid): " + sourcePath, ze)
//        } catch (ioe: IOException) {
//            throw IOException("❌ IO error during JAR extension: " + sourcePath, ioe)
//        }
//    }
//
//
//    @Throws(IOException::class)
//    private fun deleteDirectories(
//        dir: Path
//    ) {
//        if (Files.exists(dir)) {
//            Files.walk(dir)
//                .sorted(Comparator.reverseOrder<Path>())
//                .map<File?> { obj: Path? -> obj!!.toFile() }
//                .forEach { obj: File? -> obj!!.delete() }
//        }
//    }
//
//    companion object {
//        private const val MODULE_INFO_JAVA_FILENAME = "module-info.java"
//        private const val MODULE_INFO_CLASS_FILENAME = "module-info.class"
//
//        private val PATTERN_MAJOR_VERSION: Pattern = Pattern.compile("(?<major>\\d+)\\.[^\\.]+\\.[^\\.]+")
//
//        private val DEFAULT_REALMS: MutableMap<kotlin.String?, kotlin.String?> = initDefaultRealms()
//
//        private fun atLeastVersion(
//            tool: ToolProvider,
//            major: Int
//        ): Boolean {
//            val out = StringWriter()
//            val err = StringWriter()
//            tool.run(
//                PrintWriter(out),
//                PrintWriter(err),
//                "--version"
//            )
//
//            val matcher: Matcher = PATTERN_MAJOR_VERSION.matcher(out.toString())
//            return matcher.find() && matcher.group("major").toInt() >= major
//        }
//
//        private fun initDefaultRealms(): MutableMap<kotlin.String?, kotlin.String?> {
//            return Collections.singletonMap<kotlin.String?, kotlin.String?>(
//                "maven.pkg.github.com",
//                "GitHub Package Registry"
//            )
//        }
//    }
//}