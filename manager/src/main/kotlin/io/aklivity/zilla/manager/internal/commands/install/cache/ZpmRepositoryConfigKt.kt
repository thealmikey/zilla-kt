package io.aklivity.zilla.manager.internal.commands.install.cache

import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.internal.impl.DefaultRepositorySystem
import org.eclipse.aether.internal.impl.DefaultServiceLocator.ErrorHandler
import org.eclipse.aether.util.repository.AuthenticationBuilder
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy
import org.eclipse.aether.DefaultRepositorySystemSession
import io.github.oshai.kotlinlogging.KotlinLogging
import org.eclipse.aether.spi.locator.DefaultServiceLocator
import org.eclipse.aether.impl.DefaultServiceLocator.ErrorHandler


import java.nio.file.Path

object ZpmRepositoryConfigKt {

    val logger =io.github.oshai.kotlinlogging.KotlinLogging.Logger {}

    fun newRepositorySystem(): RepositorySystem {
        val locator = DefaultServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        locator.setErrorHandler(object : ErrorHandler() {
            override fun serviceCreationFailed(type: Class<*>, impl: Class<*>, exception: Throwable) {
                println("Aether service creation failed for $type: ${exception.message}")
            }
        })

        return locator.getService(RepositorySystem::class.java)
    }

    fun newRepositorySystemSession(system: RepositorySystem, localRepoPath: Path): RepositorySystemSession {
        val session = DefaultRepositorySystemSession()
        val localRepo = LocalRepository(localRepoPath.toFile())
        session.localRepositoryManager = system.newLocalRepositoryManager(session, localRepo)
        return session
    }

    fun defaultRepositories(): List<RemoteRepository> = listOf(
        RemoteRepository.Builder(
            "central", "default", "https://repo.maven.apache.org/maven2/"
        ).build()
    )
}
