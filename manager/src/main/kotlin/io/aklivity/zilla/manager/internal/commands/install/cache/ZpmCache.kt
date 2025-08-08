package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.either
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.resolution.DependencyRequest
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

// Class to manage dependency resolution and caching using Maven Aether
open class ZpmCacheKt(
    // List of remote repositories to query for artifacts
    private val repositories: List<RemoteRepository>,
    // Local directory for caching resolved artifacts
    private val localCacheDir: Path,
    // Aether repository system for dependency resolution
    private val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem(),
    // Aether session for managing resolution context and local repository
    private val session: RepositorySystemSession = ZpmRepositoryConfigKt.newRepositorySystemSession(system, localCacheDir)
) {
    // Logger for debugging and error reporting
    private val logger = LoggerFactory.getLogger(ZpmCacheKt::class.java)

    // Resolves a list of dependencies, excluding specified dependencies, and returns resolved artifacts
    fun resolve(
        dependencies: List<ZpmDependencyKt>,
        exclusions: List<ZpmDependencyKt>
    ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> = either {
        logger.info("Resolving dependencies: {}, exclusions: {}", dependencies, exclusions)

        // List to store resolved artifacts
        val result = mutableListOf<ZpmArtifactKt>()

        // Process each dependency
        for (dep in dependencies) {
            either {
                // Validate and construct artifact GAV (groupId:artifactId:version)
                val artifact = try {
                    DefaultArtifact("${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}")
                } catch (e: IllegalArgumentException) {
                    // Handle malformed GAV coordinates
                    logger.error("Invalid dependency format: {}", dep, e)
                    raise(ZpmResolutionErrorKt.InvalidDependencyError("Invalid dependency: ${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}"))
                }

                // Create a dependency with compile scope
                val dependency = Dependency(artifact, "compile")
                // Create a collect request to gather dependency graph
                val collectRequest = CollectRequest(dependency, repositories)
                // Create a dependency request with no filtering
                val dependencyRequest = DependencyRequest(collectRequest, null)

                // Resolve the dependency and its transitive dependencies
                val dependencyResult = Either.catch {
                    system.resolveDependencies(session, dependencyRequest)
                }.mapLeft {
                    // Handle resolution failures (e.g., artifact not found)
                    logger.error("Dependency resolution failed for {}: {}", artifact, it.message, it)
                    ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error", it)
                }.bind()

                // Process the main artifact (first result is the requested artifact)
                val artifactResult = dependencyResult.artifactResults.firstOrNull()
                val file = artifactResult?.artifact?.file?.toPath()
                if (file != null && Files.exists(file)) {
                    val artifactId = ZpmArtifactIdKt(artifact.groupId, artifact.artifactId, artifact.version)
                    // Add resolved artifact to result list
                    result.add(
                        ZpmArtifactKt(
                            id = artifactId,
                            path = file,
                            // Note: Exclusions not applied here; add logic if needed
                            dependencies = emptySet() // Transitive dependencies not included for resolve
                        )
                    )
                    logger.debug("Resolved: {} → {}", artifactId, file)
                } else {
                    // Raise error if artifact file is missing
                    raise(ZpmResolutionErrorKt.DependencyResolutionError("Artifact not found: $artifact"))
                }
            }.mapLeft {
                // Log and propagate errors for this dependency
                logger.error("Failed to resolve dependency {}: {}", dep, it)
                it
            }.bind()
        }

        result
    }

    // Resolves a list of import dependencies and their transitive dependencies, returning a map of artifacts
    open fun resolveImports(
        imports: List<ZpmDependencyKt>
    ): Either<ZpmResolutionErrorKt, Map<ZpmArtifactIdKt, ZpmArtifactKt>> = either {
        logger.info("Resolving imports: {}", imports)

        // Map to store resolved artifacts
        val result = mutableMapOf<ZpmArtifactIdKt, ZpmArtifactKt>()

        // Process each import dependency
        for (dep in imports) {
            // Validate and construct artifact GAV
            val artifact = try {
                DefaultArtifact("${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}")
            } catch (e: IllegalArgumentException) {
                logger.error("Invalid dependency format: {}", dep, e)
                raise(ZpmResolutionErrorKt.InvalidDependencyError("Invalid dependency: ${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}"))
            }

            // Create a dependency with compile scope
            val dependency = Dependency(artifact, "compile")
            // Create a collect request to gather dependency graph
            val collectRequest = CollectRequest(dependency, repositories)
            // Create a dependency request with no filtering
            val dependencyRequest = DependencyRequest(collectRequest, null)

            // Resolve the dependency and its transitive dependencies
            val dependencyResult = Either.catch {
                system.resolveDependencies(session, dependencyRequest)
            }.mapLeft {
                logger.error("Dependency resolution failed for {}: {}", artifact, it.message, it)
                ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error", it)
            }.bind()

            // Process each resolved artifact (main and transitive dependencies)
            dependencyResult.artifactResults.forEach { artifactResult ->
                val file = artifactResult.artifact.file?.toPath()
                val a = artifactResult.artifact
                if (file != null && Files.exists(file)) {
                    val artifactId = ZpmArtifactIdKt(a.groupId, a.artifactId, a.version)

                    // Collect transitive dependency IDs
                    val deps = dependencyResult.root.children.mapNotNull { child ->
                        child.dependency?.artifact?.let { c ->
                            ZpmArtifactIdKt(c.groupId, c.artifactId, c.version)
                        }
                    }.toSet()

                    // Add to result map
                    result[artifactId] = ZpmArtifactKt(
                        id = artifactId,
                        path = file,
                        dependencies = deps
                    )
                    logger.debug("Resolved: {} → {}", artifactId, file)
                } else {
                    // Raise error if artifact file is missing
                    raise(ZpmResolutionErrorKt.DependencyResolutionError("Artifact not found: ${dep.groupId}:${dep.artifactId}:${dep.version.getOrElse { "LATEST" }}"))
                }
            }
        }

        result
    }.mapLeft {
        // Convert unexpected errors to ZpmResolutionErrorKt.UnexpectedError
        if (it is ZpmResolutionErrorKt) it else ZpmResolutionErrorKt.UnexpectedError(it.toString())
    }
}