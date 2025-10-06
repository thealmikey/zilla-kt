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


    try {
        val system: RepositorySystem = ZpmRepositoryConfigKt.newRepositorySystem()

        val localCacheDir = Paths.get(System.getProperty("user.home"), ".m2/repository")


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


        val artifact = DefaultArtifact("io.aklivity.zilla:runtime:pom:develop-SNAPSHOT")


        // Check if POM exists
        val pomPath = File(
            localCacheDir.toFile(),
            "${artifact.groupId.replace('.', '/')}/${artifact.artifactId}/${artifact.version}/${artifact.artifactId}-${artifact.version}.pom"
        )

        if (pomPath.exists()) {

            pomPath.readLines().take(10).forEach { println(it) }
        } else {

        }

        // Step 1: Read descriptor
        try {

            val descriptorRequest = ArtifactDescriptorRequest()
            descriptorRequest.artifact = artifact
            descriptorRequest.repositories = repositories

            val descriptorResult = system.readArtifactDescriptor(session, descriptorRequest)

            descriptorResult.dependencies.forEach {  }
        } catch (ex: Exception) {

            ex.printStackTrace()
        }

        // Step 2: Collect & resolve dependencies
        try {

            val collectRequest = CollectRequest()
            collectRequest.root = Dependency(artifact, "")
            collectRequest.repositories = repositories

            val rootNode = system.collectDependencies(session, collectRequest).root



            val dependencyRequest = DependencyRequest(rootNode, null)
            val result = system.resolveDependencies(session, dependencyRequest)

            val nlg = PreorderNodeListGenerator()
            rootNode.accept(nlg)

            nlg.getArtifacts(false).forEach{

            }
//            nlg.artifactPaths.forEach {  }
        } catch (dre: DependencyResolutionException) {

            dre.printStackTrace()
        } catch (ex: Exception) {

            ex.printStackTrace()
        }

    } catch (fatal: Exception) {

        fatal.printStackTrace()
    }


}
