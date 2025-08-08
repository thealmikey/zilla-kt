package io.aklivity.zilla.manager.internal.commands.install

import arrow.core.Either
import arrow.core.raise.either
import io.aklivity.zilla.manager.internal.commands.install.cache.*
import io.aklivity.zilla.manager.internal.commands.install.impl.JarCopier
import io.aklivity.zilla.manager.internal.commands.install.impl.ManifestMerger
import io.aklivity.zilla.manager.internal.commands.install.impl.ModuleInfoGenerator
import io.aklivity.zilla.manager.internal.commands.install.model.ZpmTemplate
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class MyZpmInstall(
    private val cache: ZpmCacheKt,
    private val installDir: Path,
    private val jarCopier: JarCopier,
    private val manifestMerger: ManifestMerger,
    private val moduleInfoGenerator: ModuleInfoGenerator,
    private val dryRun: Boolean = false,
    private val verbose: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(MyZpmInstall::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private fun ZpmError.toResolutionError(): ZpmResolutionErrorKt =
        ZpmResolutionErrorKt.DependencyResolutionError(this.toString())

    /**
     * Install from a zpm.json-style template.
     */
    fun installFromTemplate(templatePath: Path): Either<ZpmResolutionErrorKt, Path> = either {
        if (!templatePath.exists()) {
            raise(ZpmResolutionErrorKt.ArtifactNotFound(templatePath.toString()))
        }

        val templateContent = Files.readString(templatePath)
        val templateEither: Either<ZpmResolutionErrorKt, ZpmTemplate> =
            Either.catch {
                json.decodeFromString<ZpmTemplate>(templateContent)
            }.mapLeft {
                ZpmResolutionErrorKt.DependencyResolutionError("Failed to parse template: ${it.message}")
            }

        val template = when (templateEither) {
            is Either.Left -> return templateEither // early return with error
            is Either.Right -> templateEither.value
        }

// Convert coordinates to ZpmDependencyKt
        val imports: List<ZpmDependencyKt> =
            template.imports.mapNotNull { coord -> ZpmDependencyKt.fromCoordinates(coord) }

        val deps: List<ZpmDependencyKt> =
            template.dependencies.mapNotNull { coord -> ZpmDependencyKt.fromCoordinates(coord) }


        val toResolve = (imports + deps)

        logger.info("Resolved template - imports: ${imports.size}, dependencies: ${deps.size}")

        // Resolve imports -> Map<ZpmArtifactIdKt, ZpmArtifactKt>
        val artifactsMap = cache.resolveImports(toResolve).bind()

        // Make artifact paths list
        val artifactPaths = artifactsMap.values.map { it.path }

        fun ZpmError.toResolutionError(): ZpmResolutionErrorKt =
            ZpmResolutionErrorKt.DependencyResolutionError(this.toString())
        val mainJarEither = jarCopier.copyJars(
            artifactPaths,
            installDir.resolve("zilla-install.jar")
        ).mapLeft { it.toResolutionError() }
        if (mainJarEither.isLeft()) return mainJarEither
        val mainJar = mainJarEither.getOrNull()!!

        val mergedJarEither = manifestMerger.merge(
            listOf(mainJar),
            mainJar
        ).mapLeft { it.toResolutionError() }
        if (mergedJarEither.isLeft()) return mergedJarEither
        val mergedJar = mergedJarEither.getOrNull()!!

        val moduleInfoEither = moduleInfoGenerator.generate(
            mergedJar,
            installDir.resolve("module-info")
        ).mapLeft { it.toResolutionError() }
        if (moduleInfoEither.isLeft()) return moduleInfoEither
        val moduleInfoPath = moduleInfoEither.getOrNull()!!

        // 4. Write lock file (always write to disk so tests and CI can inspect it)
        writeLockFile(templatePath, artifactsMap)
    }



    private fun writeLockFile(
        templatePath: Path,
        artifacts: Map<ZpmArtifactIdKt, ZpmArtifactKt>
    ): Path {
        val lockPath = installDir.resolve("zpm.lock")
        val lines = artifacts.entries.map { "${it.key} -> ${it.value.path}" }
        try {
            Files.createDirectories(lockPath.parent)
            Files.write(lockPath, lines)
            if (verbose) println("Wrote lock file to $lockPath")
        } catch (ex: Exception) {
            // best-effort: log but do not fail install on lock-write problems
            logger.warn("Failed to write lock file: ${ex.message}")
        }
        return lockPath // ✅ always return the path
    }

}
