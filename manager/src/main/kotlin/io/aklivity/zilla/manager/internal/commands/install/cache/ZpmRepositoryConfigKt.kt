package io.aklivity.zilla.manager.internal.commands.install.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.transport.apache.ApacheTransporterFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object ZpmRepositoryConfigKt {
    private val logger = KotlinLogging.logger {}

    /**
     * Create a RepositorySystemSession builder with proper Maven local repo fallback.
     */
    fun newRepositorySystemSessionBuilder(
        system: RepositorySystem,
        preferredLocalRepoDir: Path
    ): DefaultRepositorySystemSession {
        val localRepoDir = resolveMavenLocalRepo(preferredLocalRepoDir)
        println( "⚠ WARN: Provided local repo path points to $preferredLocalRepoDir, using: $localRepoDir" )
        println("MIKE: newRepositorySystemSessionBuilder → Using Maven local repo at: $localRepoDir")

        return DefaultRepositorySystemSession().apply {
            val localRepo = LocalRepository(localRepoDir.toFile())
            localRepositoryManager = system.newLocalRepositoryManager(this, localRepo)
        }
    }

    fun newRepositorySystem(): RepositorySystem {
        return object : RepositorySystemSupplier() {
            override fun createTransporterFactories(): MutableMap<String, TransporterFactory> {
                val result = super.createTransporterFactories()
                result[ApacheTransporterFactory.NAME] =
                    ApacheTransporterFactory(getChecksumExtractor(), getPathProcessor())
                logger.debug { "Custom ApacheTransporterFactory registered" }
                return result
            }
        }.get()
    }

    fun newRepositorySystemSession(system: RepositorySystem, preferredLocalRepoDir: Path): RepositorySystemSession {
        val localRepoDir = resolveMavenLocalRepo(preferredLocalRepoDir)
        val session = MavenRepositorySystemUtils.newSession()
        val localRepo = LocalRepository(localRepoDir.toFile())
        session.localRepositoryManager = system.newLocalRepositoryManager(session, localRepo)

        println ("Created new RepositorySystemSession with local repo at $localRepoDir" )
        println("MIKE: newRepositorySystemSession → Using Maven local repo at: $localRepoDir")
        return session
    }

    fun defaultRepositories(): List<RemoteRepository> {
        return listOf(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build(),
            RemoteRepository.Builder("jitpack", "default", "https://jitpack.io").build()
        )
    }

    /**
     * Picks Maven's default local repo (~/.m2/repository) if the given directory is .zpm or non-existent.
     */
    private fun resolveMavenLocalRepo(preferred: Path): Path {
        val homeM2 = Path.of(System.getProperty("user.home"), ".m2", "repository")
        return if (preferred.endsWith(".zpm") || !preferred.exists()) {
            println("Switching to Maven default local repo: $homeM2" )
            homeM2
        } else {
            preferred
        }
    }

    /**
     * Finds the cached artifact file — checks Maven local repo first, then .zpm.
     */
    fun cacheArtifactFile(
        mavenLocalRepo: Path,
        zpmCacheDir: Path,
        groupId: String,
        artifactId: String,
        version: String,
        extension: String
    ): Path? {
        val groupPath = groupId.replace('.', '/')
        val filename = "$artifactId-$version.$extension"

        val m2Path = mavenLocalRepo.resolve(groupPath).resolve(artifactId).resolve(version).resolve(filename)
        if (Files.exists(m2Path)) {
            println("[LOCAL-M2] Found artifact: $m2Path")
            return m2Path
        }

        val zpmPath = zpmCacheDir.resolve(groupPath).resolve(artifactId).resolve(version).resolve(filename)
        if (Files.exists(zpmPath)) {
            println("[LOCAL-ZPM] Found artifact: $zpmPath")
            return zpmPath
        }

        println("Artifact not found in either Maven local or .zpm cache")
        return null
    }
}
