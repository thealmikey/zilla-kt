package io.aklivity.zilla.manager.internal.commands.install.cache

import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.supplier.RepositorySystemSupplier
import org.eclipse.aether.transport.apache.ApacheTransporterFactory

import java.nio.file.Path

object ZpmRepositoryConfigKt {
    private val logger = KotlinLogging.logger {}

    fun newRepositorySystem(): RepositorySystem {
        return object : RepositorySystemSupplier() {
            override fun createTransporterFactories(): MutableMap<String, TransporterFactory> {
                val result = super.createTransporterFactories()

                // This works because getChecksumExtractor() and getPathProcessor() are protected
                result[ApacheTransporterFactory.NAME] =
                    ApacheTransporterFactory(getChecksumExtractor(), getPathProcessor())

                return result
            }
        }.get()
    }

    fun newRepositorySystemSession(system: RepositorySystem, localRepoDir: Path): RepositorySystemSession {
        val session = MavenRepositorySystemUtils.newSession()
        val localRepo = LocalRepository(localRepoDir.toFile())
        session.localRepositoryManager = system.newLocalRepositoryManager(session, localRepo)
        return session
    }

    fun defaultRepositories(): List<RemoteRepository> {
        return listOf(
            RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build(),
            RemoteRepository.Builder("jitpack", "default", "https://jitpack.io").build()
        )
    }
}
