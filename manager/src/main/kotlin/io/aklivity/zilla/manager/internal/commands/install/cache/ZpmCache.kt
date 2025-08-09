package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.AbstractRepositoryListener
import org.eclipse.aether.RepositoryEvent
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.resolution.*
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import org.eclipse.aether.util.artifact.JavaScopes
import org.eclipse.aether.util.graph.visitor.NodeListGenerator
import org.eclipse.aether.util.graph.visitor.PreorderDependencyNodeConsumerVisitor
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap

open class ZpmCacheKt(
    private val repositories: List<RemoteRepository>,
    private val localCacheDir: Path = Paths.get(System.getProperty("user.home"), ".m2", "repository"),
    private val zpmCacheDir: Path = Paths.get(System.getProperty("user.home"), ".zpm", "cache")
) {
    private val logger = KotlinLogging.logger {}
    private val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem()
    private val seenArtifacts = ConcurrentHashMap.newKeySet<String>()

    private val session: RepositorySystemSession =
        ZpmRepositoryConfigKt.newRepositorySystemSessionBuilder(system, localCacheDir)
            .setRepositoryListener(LoggingRepositoryListener())
            .setTransferListener(LoggingTransferListener())
            .setConfigProperty("aether.conflictResolver.verbose", true)
            .let { baseSession ->
                object : RepositorySystemSession by baseSession {
                    override fun getLocalRepository(): LocalRepository = LocalRepository(localCacheDir.toFile())
                }
            }

    open fun resolveImports(imports: List<ZpmDependencyKt>, dependencies: List<ZpmDependencyKt>): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> {
        logger.debug { "[START] Resolving imports=$imports, dependencies=$dependencies (localCacheDir=$localCacheDir)" }
        val artifacts = mutableListOf<ZpmArtifactKt>()
        val imported = mutableMapOf<ZpmDependencyKt, String>()

        // Step 1: Resolve imports (POMs) to extract managed dependencies
        imports.forEach { imp ->
            try {
                val artifactVersion = imp.version.getOrElse { "develop-SNAPSHOT" }
                val artifact = DefaultArtifact(imp.groupId, imp.artifactId, "pom", artifactVersion)
                val artifactIdStr = "${imp.groupId}:${imp.artifactId}:$artifactVersion"
                logger.debug { "Resolving import $artifactIdStr" }

                if (!seenArtifacts.add(artifactIdStr)) return@forEach

                val descriptorRequest = ArtifactDescriptorRequest(artifact, repositories, null)
                val descriptorResult = try {
                    system.readArtifactDescriptor(session, descriptorRequest)
                } catch (e: ArtifactDescriptorException) {
                    logger.warn { "Failed to read descriptor for $artifactIdStr: ${e.message}" }
                    return ZpmResolutionErrorKt.DependencyResolutionError(artifactIdStr, e).left()
                }
                descriptorResult.managedDependencies.forEach { dep ->
                    val managedArtifact = dep.artifact
                    imported[ZpmDependencyKt(managedArtifact.groupId, managedArtifact.artifactId, arrow.core.none())] =
                        managedArtifact.version
                }
                logger.debug { "Imported versions for $artifactIdStr: $imported" }
            } catch (e: Exception) {
                return ZpmResolutionErrorKt.DependencyResolutionError("${imp.groupId}:${imp.artifactId}", e).left()
            }
        }

        // Step 2: Resolve dependencies (JARs or POMs if JAR unavailable)
        val collectRequest = CollectRequest()
        dependencies.forEach { dep ->
            val version = dep.version.getOrElse { imported[ZpmDependencyKt(dep.groupId, dep.artifactId, arrow.core.none())] ?: "develop-SNAPSHOT" }
            val artifact = DefaultArtifact(dep.groupId, dep.artifactId, "jar", version)
            collectRequest.addDependency(Dependency(artifact, JavaScopes.COMPILE))
        }
        repositories.forEach { collectRequest.addRepository(it) }

        val dependencyRequest = DependencyRequest(collectRequest, null)
        val dependencyResult = try {
            system.resolveDependencies(session, dependencyRequest)
        } catch (e: Exception) {
            logger.error(e) { "Failed to resolve dependencies" }
            return ZpmResolutionErrorKt.DependencyResolutionError("dependencies", e).left()
        }

        // Step 3: Process dependency nodes
        val nlg = NodeListGenerator()
        dependencyResult.root.accept(PreorderDependencyNodeConsumerVisitor(nlg))
        val nodesWithDependencies = nlg.getNodesWithDependencies()

        nodesWithDependencies.forEach { node ->
            val dep = node.dependency ?: return@forEach
            val artifact = dep.artifact
            val artifactIdStr = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
            if (!seenArtifacts.add(artifactIdStr)) return@forEach

            val id = ZpmArtifactIdKt.parse(artifactIdStr)
            val dependencies = node.children.mapNotNull { child ->
                child.dependency?.artifact?.let {
                    ZpmArtifactIdKt.parse("${it.groupId}:${it.artifactId}:${it.version}")
                }
            }.toSet()

            // Cache artifact file (JAR or POM)
            val artifactPath = try {
                val artifactResult = system.resolveArtifact(session, ArtifactRequest(artifact, repositories, null))
                artifactResult.artifact.file?.toPath()?.let { file ->
                    cacheArtifactFile(file, artifactIdStr) ?: file
                }
            } catch (e: ArtifactResolutionException) {
                val pomPath = localCacheDir.resolve("${artifact.groupId.replace('.', '/')}/${artifact.artifactId}/${artifact.version}/${artifact.artifactId}-${artifact.version}.pom")
                logger.debug { "No JAR for $artifactIdStr, attempting POM: $pomPath" }
                val pomArtifact = DefaultArtifact(artifact.groupId, artifact.artifactId, "pom", artifact.version)
                try {
                    val pomResult = system.resolveArtifact(session, ArtifactRequest(pomArtifact, repositories, null))
                    pomResult.artifact.file?.toPath()?.let { file ->
                        cacheArtifactFile(file, artifactIdStr) ?: file
                    } ?: pomPath
                } catch (e: ArtifactResolutionException) {
                    logger.warn { "POM file not found for $artifactIdStr at $pomPath: ${e.message}" }
                    return ZpmResolutionErrorKt.DependencyResolutionError(artifactIdStr, Exception("POM file missing")).left()
                }
            } ?: run {
                logger.warn { "No file resolved for $artifactIdStr" }
                return ZpmResolutionErrorKt.DependencyResolutionError(artifactIdStr, Exception("Artifact file missing")).left()
            }

            artifacts.add(ZpmArtifactKt(id, artifactPath, dependencies))
            logger.debug { "Added artifact $artifactIdStr: path=$artifactPath, dependencies=$dependencies" }
        }

        logger.debug { "[DONE] Resolved artifacts: ${artifacts.map { it.id }}" }
        return artifacts.right()
    }

    private fun cacheArtifactFile(artifactFile: Path, gav: String): Path? {
        val parts = gav.split(":")
        require(parts.size == 3) { "Invalid GAV: $gav" }
        val (groupId, artifactId, version) = parts

        val targetPath = zpmCacheDir
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactFile.fileName)

        try {
            java.nio.file.Files.createDirectories(targetPath.parent)
            if (!java.nio.file.Files.exists(targetPath) || java.nio.file.Files.size(artifactFile) != java.nio.file.Files.size(targetPath)) {
                java.nio.file.Files.copy(artifactFile, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                logger.debug { "[CACHE] Cached $gav → $targetPath" }
            } else {
                logger.debug { "[CACHE] Skipped caching $gav (already present)" }
            }
            return targetPath
        } catch (e: Exception) {
            logger.warn { "Failed to cache $gav to $targetPath: ${e.message}" }
            return null
        }
    }

    private class LoggingRepositoryListener : AbstractRepositoryListener() {
        private val logger = KotlinLogging.logger {}
        override fun artifactResolved(event: RepositoryEvent) {
            logger.info { "[DOWNLOAD] ${event.artifact} from ${event.repository?.id}" }
        }
        override fun artifactDescriptorMissing(event: RepositoryEvent) {
            logger.warn { "Missing artifact descriptor for ${event.artifact}" }
        }
        override fun artifactResolving(event: RepositoryEvent) {
            logger.debug { "Resolving artifact ${event.artifact}" }
        }
    }

    private class LoggingTransferListener : AbstractTransferListener() {
        private val logger = KotlinLogging.logger {}
        private val formatter = DecimalFormat("0.0")
        override fun transferStarted(event: TransferEvent) {
            logger.info { "[TRANSFER-START] ${event.resource.resourceName}" }
        }
        override fun transferProgressed(event: TransferEvent) {
            val res: TransferResource = event.resource
            val kb = event.dataLength / 1024.0
            val totalKb = res.contentLength / 1024.0
            logger.info { "[PROGRESS] ${res.resourceName} - ${formatter.format(kb)}/${formatter.format(totalKb)} KB" }
        }
        override fun transferSucceeded(event: TransferEvent) {
            logger.info { "[TRANSFER-DONE] ${event.resource.resourceName}" }
        }
    }
}