package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.DependencyRequest
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

open class ZpmCacheKt(
    private val repositories: List<RemoteRepository>,
    private val localCacheDir: Path = Paths.get(System.getProperty("user.home"), ".m2/repository"),
    private val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem(),
    private val session: RepositorySystemSession = ZpmRepositoryConfigKt.newRepositorySystemSession(system, localCacheDir).let { baseSession ->
        object : RepositorySystemSession by baseSession {
            override fun isOffline(): Boolean = true
        }
    }
) {
    private val logger = LoggerFactory.getLogger(ZpmCacheKt::class.java)

    fun resolve(
        dependencies: List<ZpmDependencyKt>,
        exclusions: List<ZpmDependencyKt>
    ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = either {
        logger.info("Resolving dependencies: {}, exclusions: {}", dependencies, exclusions)

        val result = mutableListOf<ZpmArtifactKt>()

        for (dep in dependencies) {
            either {
                val artifact = try {
                    DefaultArtifact("${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}")
                } catch (e: IllegalArgumentException) {
                    logger.error("Invalid dependency format: {}", dep, e)
                    raise(ZpmResolutionErrorKt.InvalidDependencyError("Invalid dependency: ${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}"))
                }

                val dependency = Dependency(artifact, "compile")
                val collectRequest = CollectRequest(dependency, repositories)
                val dependencyRequest = DependencyRequest(collectRequest, null)

                val dependencyResult = Either.catch {
                    system.resolveDependencies(session, dependencyRequest)
                }.mapLeft {
                    logger.error("Dependency resolution failed for {}: {}", artifact, it.message, it)
                    ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error", it)
                }.bind()

                val artifactResult = dependencyResult.artifactResults.firstOrNull()
                val file = artifactResult?.artifact?.file?.toPath()
                if (file != null && Files.exists(file)) {
                    val artifactId = ZpmArtifactIdKt(artifact.groupId, artifact.artifactId, artifact.version)
                    result.add(
                        ZpmArtifactKt(
                            id = artifactId,
                            path = file,
                            dependencies = emptySet()
                        )
                    )
                    logger.debug("Resolved: {} → {}", artifactId, file)
                } else {
                    raise(ZpmResolutionErrorKt.DependencyResolutionError("Artifact not found: $artifact"))
                }
            }.mapLeft {
                logger.error("Failed to resolve dependency {}: {}", dep, it)
                it
            }.bind()
        }

        result
    }

    open fun resolveImports(
        imports: List<ZpmDependencyKt>
    ): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> = either {
        logger.info("Resolving imports: {}", imports)

        val result = mutableMapOf<ZpmArtifactIdKt, ZpmArtifactKt>()

        for (dep in imports) {
            val artifact = try {
                DefaultArtifact("${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}")
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid dependency format: {}", dep, e)
                raise(ZpmResolutionErrorKt.InvalidDependencyError("Invalid dependency: ${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}"))
            }

            val dependency = Dependency(artifact, "compile")
            val collectRequest = CollectRequest(dependency, repositories)
            val dependencyRequest = DependencyRequest(collectRequest, null)

            val dependencyResult = Either.catch {
                system.resolveDependencies(session, dependencyRequest)
            }.mapLeft {
                logger.error("Dependency resolution failed for {}: {}", artifact, it.message, it)
                ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error", it)
            }.bind()

            dependencyResult.artifactResults.forEach { artifactResult ->
                val file = artifactResult.artifact.file?.toPath()
                val a = artifactResult.artifact
                if (file != null && Files.exists(file)) {
                    val artifactId = ZpmArtifactIdKt(a.groupId, a.artifactId, a.version)
                    val deps = dependencyResult.root.children.mapNotNull { child ->
                        child.dependency?.artifact?.let { c ->
                            ZpmArtifactIdKt(c.groupId, c.artifactId, c.version)
                        }
                    }.toSet()

                    result[artifactId] = ZpmArtifactKt(
                        id = artifactId,
                        path = file,
                        dependencies = deps
                    )
                    logger.debug("Resolved: {} → {}", artifactId, file)
                } else {
                    raise(ZpmResolutionErrorKt.DependencyResolutionError("Artifact not found: ${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}"))
                }
            }
        }

        result
    }.mapLeft {
        if (it is ZpmResolutionErrorKt) it else ZpmResolutionErrorKt.UnexpectedError(it.toString())
    }
}