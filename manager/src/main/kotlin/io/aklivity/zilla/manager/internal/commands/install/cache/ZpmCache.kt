package io.aklivity.zilla.manager.internal.commands.install.cache

import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactIdKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmArtifactKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmDependencyKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmRepositoryConfigKt
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import org.eclipse.aether.artifact.Artifact
import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.*
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.*
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import org.eclipse.aether.util.artifact.JavaScopes
import org.eclipse.aether.util.graph.traverser.FatArtifactTraverser
import org.eclipse.aether.util.graph.visitor.NodeListGenerator
import org.eclipse.aether.util.graph.visitor.PreorderDependencyNodeConsumerVisitor
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.text.DecimalFormat
import java.util.concurrent.ConcurrentHashMap

open class ZpmCacheKt(
    var repositories: MutableList<RemoteRepository>,
    private val localCacheDir: Path = Paths.get(System.getProperty("user.home"), ".m2", "repository"),
    private val zpmCacheDir: Path = Paths.get(System.getProperty("user.home"), ".zpm", "cache")
) {
    private val logger = KotlinLogging.logger {}
    private val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem()
    private val seenArtifacts = ConcurrentHashMap.newKeySet<String>()

    private val session: RepositorySystemSession =
        ZpmRepositoryConfigKt.newRepositorySystemSessionBuilder(system, localCacheDir)
            .setSystemProperties(System.getProperties())
            .setDependencySelector(
                org.eclipse.aether.util.graph.selector.AndDependencySelector(
                    org.eclipse.aether.util.graph.selector.ScopeDependencySelector("test", "provided"),
                    org.eclipse.aether.util.graph.selector.OptionalDependencySelector(),
                    org.eclipse.aether.util.graph.selector.ExclusionDependencySelector()
                )
            )
            .setDependencyManager(org.eclipse.aether.util.graph.manager.ClassicDependencyManager())
            .let { baseSession ->
                object : RepositorySystemSession by baseSession {
                    override fun getArtifactDescriptorPolicy(): ArtifactDescriptorPolicy {
                        return SimpleArtifactDescriptorPolicy(true, true)
                    }
                    override fun isOffline(): Boolean = false
                    override fun getLocalRepository(): LocalRepository = LocalRepository(localCacheDir.toFile())
                }
            }

    open fun resolveImports(
        imports: List<ZpmDependencyKt>,
        dependencies: List<ZpmDependencyKt>
    ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> {
        val artifacts = mutableListOf<ZpmArtifactKt>()
        val imported = mutableMapOf<ZpmDependencyKt, String>()

        // Step 1: Resolve imports (POMs) with soft failure
        imports.forEach { imp ->
            try {
                val artifactVersion = imp.version.getOrElse { "develop-SNAPSHOT" }
                val artifact = DefaultArtifact(imp.groupId, imp.artifactId, "pom", artifactVersion)
                val artifactIdStr = "${imp.groupId}:${imp.artifactId}:$artifactVersion"

                if (!seenArtifacts.add(artifactIdStr)) {
                    logger.debug { "Skipping duplicate import: $artifactIdStr" }
                    return@forEach
                }

                val localPom = localFileForArtifact(artifact)
                if (localPom.exists()) {
                    logger.debug { "Found local POM for $artifactIdStr at $localPom" }
                } else {
                    logger.warn { "POM not found locally for $artifactIdStr at $localPom, attempting remote resolution" }
                }

                val descriptorRequest = ArtifactDescriptorRequest(artifact, repositories, null)
                val descriptorResult = try {
                    system.readArtifactDescriptor(session, descriptorRequest)
                } catch (e: ArtifactDescriptorException) {
                    logger.warn { "Failed to resolve POM for $artifactIdStr: ${e.message}. Skipping import." }
                    return@forEach // Soft failure: skip this import
                }

                descriptorResult.managedDependencies.forEach { dep ->
                    imported[ZpmDependencyKt(dep.artifact.groupId, dep.artifact.artifactId, arrow.core.none())] =
                        dep.artifact.version
                }
            } catch (e: Exception) {
                logger.warn { "Error processing import ${imp.groupId}:${imp.artifactId}: ${e.message}. Skipping import." }
                return@forEach // Soft failure: skip this import
            }
        }

        // Step 2: Create managed dependencies from zpm.json to enforce versions
        val managedDependencies = dependencies.mapNotNull { dep ->
            dep.version.getOrElse {
                imported[ZpmDependencyKt(dep.groupId, dep.artifactId, arrow.core.none())]
            }?.let { version ->
                Dependency(DefaultArtifact(dep.groupId, dep.artifactId, "jar", version), JavaScopes.COMPILE)
            }
        }

        // Step 3: Resolve dependencies
        val collectRequest = CollectRequest()
        dependencies.forEach { dep ->
            val version = dep.version.getOrElse {
                imported[ZpmDependencyKt(dep.groupId, dep.artifactId, arrow.core.none())] ?: "develop-SNAPSHOT"
            }
            val artifact = DefaultArtifact(dep.groupId, dep.artifactId, "jar", version)
            collectRequest.addDependency(Dependency(artifact, JavaScopes.COMPILE))
        }
        repositories.forEach { collectRequest.addRepository(it) }
        collectRequest.setManagedDependencies(managedDependencies)

        val dependencyResult = try {
            system.resolveDependencies(session, DependencyRequest(collectRequest, null))
        } catch (e: Exception) {
            logger.error { "Failed to resolve dependencies: ${e.message}" }
            e.printStackTrace()
            return ZpmResolutionErrorKt.DependencyResolutionError("dependencies", e).left()
        }

        // Step 4: Process artifacts
        val nlg = NodeListGenerator()
        dependencyResult.root.accept(PreorderDependencyNodeConsumerVisitor(nlg))
        nlg.getNodesWithDependencies().forEach { node ->
            val dep = node.dependency ?: return@forEach
            val artifact = dep.artifact
            val artifactIdStr = "${artifact.groupId}:${artifact.artifactId}:${artifact.version}"
            if (!seenArtifacts.add(artifactIdStr)) {
                logger.debug { "Skipping duplicate artifact: $artifactIdStr" }
                return@forEach
            }

            val artifactPath = resolveArtifactFromZpmOrM2(artifact)
            if (artifactPath != null) {
                val id = ZpmArtifactIdKt.parse(artifactIdStr)
                val deps = node.children.mapNotNull { child ->
                    child.dependency?.artifact?.let {
                        ZpmArtifactIdKt.parse("${it.groupId}:${it.artifactId}:${it.version}")
                    }
                }.toSet()
                artifacts.add(ZpmArtifactKt(id, artifactPath, deps))
            } else {
                logger.error { "Failed to resolve artifact $artifactIdStr: Artifact missing" }
                return ZpmResolutionErrorKt.DependencyResolutionError(artifactIdStr, Exception("Artifact missing")).left()
            }
        }

        return artifacts.right()
    }

    private fun resolveArtifactFromZpmOrM2(artifact: org.eclipse.aether.artifact.Artifact): Path? {
        val zpmPath = zpmFileForArtifact(artifact)
        if (zpmPath.exists()) {
            logger.debug { "Found artifact in ZPM cache: $zpmPath" }
            return zpmPath.toPath()
        }

        val localFile = localFileForArtifact(artifact)
        if (localFile.exists()) {
            logger.debug { "Found artifact in local Maven repo: $localFile" }
            return cacheArtifactFile(localFile.toPath(), "${artifact.groupId}:${artifact.artifactId}:${artifact.version}")
        }

        return try {
            val artifactResult = system.resolveArtifact(session, ArtifactRequest(artifact, repositories, null))
            artifactResult.artifact.file?.toPath()?.let {
                logger.debug { "Resolved artifact remotely: $it" }
                cacheArtifactFile(it, "${artifact.groupId}:${artifact.artifactId}:${artifact.version}")
            }
        } catch (e: ArtifactResolutionException) {
            logger.warn { "Failed to resolve artifact ${artifact.groupId}:${artifact.artifactId}:${artifact.version} remotely: ${e.message}" }
            null
        }
    }

    private fun localFileForArtifact(artifact: Artifact): File {
        val lrm = session.localRepositoryManager
        return lrm.repository.basedir
            .toPath()
            .resolve(lrm.getPathForLocalArtifact(artifact))
            .toFile()
    }

    private fun zpmFileForArtifact(artifact: Artifact): File {
        return zpmCacheDir
            .resolve(artifact.groupId.replace('.', '/'))
            .resolve(artifact.artifactId)
            .resolve(artifact.version)
            .resolve("${artifact.artifactId}-${artifact.version}.${artifact.extension}")
            .toFile()
    }

    private fun cacheArtifactFile(artifactFile: Path, gav: String): Path? {
        val parts = gav.split(":")
        val (groupId, artifactId, version) = parts
        val targetPath = zpmCacheDir
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve(artifactFile.fileName)

        try {
            Files.createDirectories(targetPath.parent)
            Files.copy(artifactFile, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            logger.debug { "Cached artifact to ZPM: $targetPath" }
            return targetPath
        } catch (e: Exception) {
            logger.warn { "Failed to cache artifact $gav: ${e.message}" }
            return null
        }
    }
}