package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
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
    private val repositories: List<RemoteRepository>,
    private val localCacheDir: Path = Paths.get(System.getProperty("user.home"), ".m2", "repository"),
    private val zpmCacheDir: Path = Paths.get(System.getProperty("user.home"), ".zpm", "cache")
) {
    private val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem()
    private val seenArtifacts = ConcurrentHashMap.newKeySet<String>()

    private val session: RepositorySystemSession =
        ZpmRepositoryConfigKt.newRepositorySystemSessionBuilder(system, localCacheDir)
//            .setTransferListener(LoggingTransferListener())
            .setSystemProperties(System.getProperties())
//            .setSystemProperties(
//                System.getProperties().apply {
//                    this["maven.wagon.http.ssl.insecure"] = "true"
//                    this["maven.wagon.http.ssl.allowall"] = "true"
//                    this["maven.wagon.http.ssl.ignore.validity.dates"] = "true"
//                    this["maven.artifact.skipSignatures"] = "true"
//                    this["maven.artifact.threads"] = "4"
//                    this["maven.dependency.ignore"] = "true"
//                    this["maven.parallel"] = "true"
//                }
//            )
            .setDependencySelector(
                org.eclipse.aether.util.graph.selector.AndDependencySelector(
                    org.eclipse.aether.util.graph.selector.ScopeDependencySelector("test", "provided"),
                    org.eclipse.aether.util.graph.selector.OptionalDependencySelector(),
                    org.eclipse.aether.util.graph.selector.ExclusionDependencySelector()
                )
            )
            // Optionally add other defaults if needed (e.g., if not set in ZpmRepositoryConfigKt)
            .setDependencyManager(org.eclipse.aether.util.graph.manager.ClassicDependencyManager())
            .let{ baseSession ->
                object : RepositorySystemSession by baseSession {
                    // Force offline to ensure local repo usage only (optional, comment out if fetching remotes)
                    override fun getArtifactDescriptorPolicy(): ArtifactDescriptorPolicy {
                        // ignore missing/invalid POMs
                        return SimpleArtifactDescriptorPolicy(
                            true,
                             true
                        )
                    }
                    override fun isOffline(): Boolean = false
                    override fun getLocalRepository(): LocalRepository = LocalRepository(localCacheDir.toFile())
                }
            }

    open fun resolveImports(
        imports: List<ZpmDependencyKt>,
        dependencies: List<ZpmDependencyKt>
    ): Either<ZpmResolutionErrorKt, List<ZpmArtifactKt>> {
        println("=== [START] Resolving imports=${imports.size}, dependencies=${dependencies.size} ===")

        val artifacts = mutableListOf<ZpmArtifactKt>()
        val imported = mutableMapOf<ZpmDependencyKt, String>()

        // Step 1: Resolve imports (POMs)
        imports.forEach { imp ->
            try {
                val artifactVersion = imp.version.getOrElse { "develop-SNAPSHOT" }
                val artifact = DefaultArtifact(imp.groupId, imp.artifactId, "pom", artifactVersion)
                val artifactIdStr = "${imp.groupId}:${imp.artifactId}:$artifactVersion"
                println("[IMPORT] Checking $artifactIdStr")

                if (!seenArtifacts.add(artifactIdStr)) {
                    println("  ↳ Skipping $artifactIdStr (already processed)")
                    return@forEach
                }

                val localPom = localFileForArtifact(artifact)
                println("  [DEBUG] Expected POM path in .m2 → $localPom")
                if (localPom.exists()) {
                    println("  [LOCAL] Found POM in .m2: $localPom")
                } else {
                    println("  [REMOTE] POM not in .m2, will fetch if needed…")
                }

                val descriptorRequest = ArtifactDescriptorRequest(artifact, repositories, null)
                val descriptorResult = try {
                    system.readArtifactDescriptor(session, descriptorRequest)
                } catch (e: ArtifactDescriptorException) {
                    println("  [ERROR] Failed to read descriptor for $artifactIdStr → ${e.message}")
                    e.printStackTrace()
                    return ZpmResolutionErrorKt.DependencyResolutionError(artifactIdStr, e).left()
                }

                descriptorResult.managedDependencies.forEach { dep ->
                    imported[ZpmDependencyKt(dep.artifact.groupId, dep.artifact.artifactId, arrow.core.none())] =
                        dep.artifact.version
                }
            } catch (e: Exception) {
                println("[ERROR] Exception in import resolution: ${e.message}")
                e.printStackTrace()
                return ZpmResolutionErrorKt.DependencyResolutionError("${imp.groupId}:${imp.artifactId}", e).left()
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
            println("[DEP] Adding dependency ${dep.groupId}:${dep.artifactId}:$version")
            val artifact = DefaultArtifact(dep.groupId, dep.artifactId, "jar", version)
            collectRequest.addDependency(Dependency(artifact, JavaScopes.COMPILE))
        }
        repositories.forEach { collectRequest.addRepository(it) }
        collectRequest.setManagedDependencies(managedDependencies) // Enforce versions from zpm.json

        val dependencyResult = try {
            system.resolveDependencies(session, DependencyRequest(collectRequest, null))
        } catch (e: Exception) {
            println("[ERROR] Dependency resolution failed: ${e.message}")
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
                println("  ↳ Skipping $artifactIdStr (already processed)")
                return@forEach
            }

            println("[RESOLVE] Trying $artifactIdStr")
            val artifactPath = resolveArtifactFromZpmOrM2(artifact)
            if (artifactPath != null) {
                println("  [OK] Resolved $artifactIdStr → $artifactPath")
                val id = ZpmArtifactIdKt.parse(artifactIdStr)
                val deps = node.children.mapNotNull { child ->
                    child.dependency?.artifact?.let {
                        ZpmArtifactIdKt.parse("${it.groupId}:${it.artifactId}:${it.version}")
                    }
                }.toSet()
                artifacts.add(ZpmArtifactKt(id, artifactPath, deps))
            } else {
                println("  [FAIL] Could not resolve $artifactIdStr")
                return ZpmResolutionErrorKt.DependencyResolutionError(artifactIdStr, Exception("Artifact missing")).left()
            }
        }

        println("=== [DONE] Resolved ${artifacts.size} artifacts ===")
        return artifacts.right()
    }

    /**
     * Check .zpm first (modularized cache), then .m2, then remote.
     */
    private fun resolveArtifactFromZpmOrM2(artifact: org.eclipse.aether.artifact.Artifact): Path? {
        val zpmPath = zpmFileForArtifact(artifact)
        if (zpmPath.exists()) {
            println("    [HIT] Found modular jar in .zpm: $zpmPath")
            return zpmPath.toPath()
        }

        val localFile = localFileForArtifact(artifact)
        if (localFile.exists()) {
            println("    [HIT] Found raw jar in .m2: $localFile")
            return cacheArtifactFile(localFile.toPath(), "${artifact.groupId}:${artifact.artifactId}:${artifact.version}")
        }

        println("    [MISS] Not in .zpm or .m2, fetching from remote…")
        return try {
            val artifactResult = system.resolveArtifact(session, ArtifactRequest(artifact, repositories, null))
            artifactResult.artifact.file?.toPath()?.let {
                cacheArtifactFile(it, "${artifact.groupId}:${artifact.artifactId}:${artifact.version}")
            }
        } catch (e: ArtifactResolutionException) {
            println("    [FAIL] Could not fetch artifact → ${e.message}")
            e.printStackTrace()
            null
        }
    }

    private fun localFileForArtifact(artifact: org.eclipse.aether.artifact.Artifact): File {
        val lrm = session.localRepositoryManager
        return lrm.repository.basedir
            .toPath()
            .resolve(lrm.getPathForLocalArtifact(artifact))
            .toFile()
    }

    private fun zpmFileForArtifact(artifact: org.eclipse.aether.artifact.Artifact): File {
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
            println("    [CACHE] Stored modularized $gav → $targetPath")
            return targetPath
        } catch (e: Exception) {
            println("    [CACHE-ERROR] Could not cache $gav → ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private class LoggingTransferListener : AbstractTransferListener() {
        private val formatter = DecimalFormat("0.0")
        override fun transferStarted(event: TransferEvent) {
            println("[TRANSFER-START] ${event.resource.resourceName}")
        }
        override fun transferProgressed(event: TransferEvent) {
            val res: TransferResource = event.resource
            val kb = event.dataLength / 1024.0
            val totalKb = res.contentLength / 1024.0
            println("[PROGRESS] ${res.resourceName} - ${formatter.format(kb)}/${formatter.format(totalKb)} KB")
        }
        override fun transferSucceeded(event: TransferEvent) {
            println("[TRANSFER-DONE] ${event.resource.resourceName}")
        }
    }
}
