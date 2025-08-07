package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Either
import arrow.core.right
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.RemoteRepository
import java.nio.file.Path
import arrow.core.*
import arrow.core.getOrElse
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.resolution.ArtifactDescriptorResult
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.spi.locator.DefaultServiceLocator
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.impl.DefaultServiceLocator.ErrorHandler

class ZpmCacheKt(
    private val repositories: List<RemoteRepository>,
    private val localCacheDir: Path,
    private val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem(),
    private val session: RepositorySystemSession = ZpmRepositoryConfigKt.newRepositorySystemSession(
        ZpmRepositoryConfigKt.newRepositorySystem(),
        localCacheDir
    )
) {

    val logger = io.github.oshai.kotlinlogging.KotlinLogging.Logger{}

    fun resolve(
        imports: List<ZpmDependencyKt>,
        dependencies: List<ZpmDependencyKt>
    ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> {
        // We'll build this in steps — for now, return empty
        return emptyList<ZpmArtifactKt>().right()
    }

fun resolveImports(
    imports: List<ZpmDependencyKt>
): Either<ZpmResolutionErrorKt, Map<ZpmDependencyKt, String>> = Either.catch {
    imports.flatMap { imp ->
        val artifactCoords = imp.toString()
        logger.info { "🔍 Resolving import: $artifactCoords" }

        val artifact = DefaultArtifact(imp.groupId, imp.artifactId, "pom", imp.version.orNull())
        val descriptorRequest = ArtifactDescriptorRequest().apply {
            setArtifact(artifact)
            repositories.forEach { addRepository(it) }
        }

        val descriptorResult: ArtifactDescriptorResult =
            system.readArtifactDescriptor(session, descriptorRequest)

        val managed = descriptorResult.managedDependencies.map { dep ->
            val art = dep.artifact
            val key = ZpmDependencyKt(art.groupId, art.artifactId)
            logger.debug { "   ➕ Managed: $key → ${art.version}" }
            key to art.version
        }

        logger.info { "✅ Imported ${managed.size} managed dependencies from $artifactCoords" }
        managed
    }.toMap()
}.mapLeft { ex ->
    logger.error(ex) { "💥 Failed to resolve imports: ${ex.message}" }
    ZpmResolutionErrorKt.ImportFailure("Failed to resolve imports", ex)
}

}
