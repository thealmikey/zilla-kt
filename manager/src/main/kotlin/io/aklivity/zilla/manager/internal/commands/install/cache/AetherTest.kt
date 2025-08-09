package io.aklivity.zilla.manager.internal.commands.install.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.ArtifactDescriptorRequest
import org.eclipse.aether.resolution.DependencyResolutionException
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator
import java.io.File
import java.nio.file.Paths

fun main() {
    val logger = KotlinLogging.logger {}
    println("=== Zilla Maven Resolver Debug Start ===")

    try {
        val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem()

        val localCacheDir = Paths.get(System.getProperty("user.home"), ".m2/repository")
        println("Local cache dir: $localCacheDir")

        val session = ZpmRepositoryConfigKt
            .newRepositorySystemSession(system, localCacheDir)
            .let { baseSession ->
                object : RepositorySystemSession by baseSession {
                    override fun isOffline(): Boolean = true
                    override fun getLocalRepository(): LocalRepository =
                        LocalRepository(localCacheDir.toFile())
                }
            }

        val repositories = listOf(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build(),
            RemoteRepository.Builder("local", "default", "file://${localCacheDir.toFile()}").build()
        )
        println("Repositories: $repositories")

        val artifact = DefaultArtifact("io.aklivity.zilla:runtime:pom:develop-SNAPSHOT")
        println("Artifact: $artifact")

        // Check if POM exists
        val pomPath = File(
            localCacheDir.toFile(),
            "${artifact.groupId.replace('.', '/')}/${artifact.artifactId}/${artifact.version}/${artifact.artifactId}-${artifact.version}.pom"
        )
        println("Expected POM path: $pomPath")
        if (pomPath.exists()) {
            println("✅ POM found (${pomPath.length()} bytes)")
            pomPath.readLines().take(10).forEach { println(it) }
        } else {
            println("❌ POM not found in local repo")
        }

        // Step 1: Read descriptor
        try {
            println("\nStep 1: Reading artifact descriptor...")
            val descriptorRequest = ArtifactDescriptorRequest()
            descriptorRequest.artifact = artifact
            descriptorRequest.repositories = repositories

            val descriptorResult = system.readArtifactDescriptor(session, descriptorRequest)
            println("Descriptor contains ${descriptorResult.dependencies.size} dependencies")
            descriptorResult.dependencies.forEach { println("  ${it.artifact}") }
        } catch (ex: Exception) {
            println("❌ Error reading descriptor: ${ex.message}")
            ex.printStackTrace()
        }

        // Step 2: Collect & resolve dependencies
        try {
            println("\nStep 2: Collecting dependencies...")
            val collectRequest = CollectRequest()
            collectRequest.root = Dependency(artifact, "")
            collectRequest.repositories = repositories

            val rootNode = system.collectDependencies(session, collectRequest).root
            println("Root node: $rootNode")

            println("\nStep 3: Resolving dependencies...")
            val dependencyRequest = DependencyRequest(rootNode, null)
            val result = system.resolveDependencies(session, dependencyRequest)

            val nlg = PreorderNodeListGenerator()
            rootNode.accept(nlg)
            println("Resolved artifacts:")
            nlg.getArtifacts(false).forEach{
                println("artifact is ${it.toString()}")
            }
//            nlg.artifactPaths.forEach { println("  $it") }
        } catch (dre: DependencyResolutionException) {
            println("❌ DependencyResolutionException: ${dre.message}")
            dre.printStackTrace()
        } catch (ex: Exception) {
            println("❌ General error in collect/resolve: ${ex.message}")
            ex.printStackTrace()
        }

    } catch (fatal: Exception) {
        println("❌ Fatal error: ${fatal.message}")
        fatal.printStackTrace()
    }

    println("=== Zilla Maven Resolver Debug End ===")
}
