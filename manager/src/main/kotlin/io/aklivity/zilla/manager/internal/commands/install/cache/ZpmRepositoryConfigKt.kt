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



        return session
    }


    /**
     * Picks Maven's default local repo (~/.m2/repository) if the given directory is .zpm or non-existent.
     */
    private fun resolveMavenLocalRepo(preferred: Path): Path {
        val homeM2 = Path.of(System.getProperty("user.home"), ".m2", "repository")
        return if (preferred.endsWith(".zpm") || !preferred.exists()) {

            homeM2
        } else {
            preferred
        }
    }


}
