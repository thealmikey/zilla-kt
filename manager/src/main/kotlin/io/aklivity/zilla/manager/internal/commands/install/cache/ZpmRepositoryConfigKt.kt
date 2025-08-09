package io.aklivity.zilla.manager.internal.commands.install.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.transport.apache.ApacheTransporterFactory
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.DefaultRepositorySystemSession
import java.nio.file.Path

object ZpmRepositoryConfigKt {
    private val logger = KotlinLogging.logger {}

    fun newRepositorySystemSessionBuilder(
        system: RepositorySystem,
        localCacheDir: Path
    ): DefaultRepositorySystemSession {
        return DefaultRepositorySystemSession().apply {
            val localRepo = LocalRepository(localCacheDir.toFile())
            localRepositoryManager = system.newLocalRepositoryManager(this, localRepo)
        }
    }

    fun newRepositorySystem(): RepositorySystem {
        return object : RepositorySystemSupplier() {
            override fun createTransporterFactories(): MutableMap<String, TransporterFactory> {
                val result = super.createTransporterFactories()

                // Replace the default Apache transporter with one using the supplier's processors
                result[ApacheTransporterFactory.NAME] =
                    ApacheTransporterFactory(getChecksumExtractor(), getPathProcessor())

                logger.debug { "Custom ApacheTransporterFactory registered" }
                return result
            }

            // We do NOT override createRepositoryConnectorFactories(),
            // so BasicRepositoryConnectorFactory and others are configured by the supplier
        }.get()
    }

    fun newRepositorySystemSession(system: RepositorySystem, localRepoDir: Path): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val localRepo = LocalRepository(localRepoDir.toFile())
        session.localRepositoryManager = system.newLocalRepositoryManager(session, localRepo)
        logger.debug { "Created new RepositorySystemSession with local repo at $localRepoDir" }
        return session
    }

    fun defaultRepositories(): List<RemoteRepository> {
        return listOf(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build(),
            RemoteRepository.Builder("jitpack", "default", "https://jitpack.io").build()
        )
    }
}
