package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.Either
import arrow.core.getOrElse
import io.aklivity.zilla.manager.internal.commands.install.cache.ZpmResolutionErrorKt
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

class DefaultJarExtender(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null,
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
) {
    private val logger = LoggerFactory.getLogger(DefaultJarExtender::class.java)
    private val retryDelay: Long = if (isWindows) 500L else 100L
    private val maxRetries: Int = 10

    fun extendJarWithRetry(
        sourcePath: Path,
        targetPath: Path,
        newEntry: JarEntry,
        newEntryPath: Path
    ): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        feedback?.invoke("📦 Starting extension of $sourcePath with ${newEntry.name} to $targetPath")
        logger.debug("Extending JAR $sourcePath to $targetPath with entry ${newEntry.name} from $newEntryPath")

        // Validate source JAR
        if (!Files.exists(sourcePath) || !Files.isReadable(sourcePath)) {
            feedback?.invoke("❌ Source JAR does not exist or is unreadable: $sourcePath")
            logger.error("Source JAR does not exist or is unreadable: $sourcePath")
            throw IOException("Source JAR does not exist or is unreadable: $sourcePath")
        }
        val sourceSize = Files.size(sourcePath)
        if (sourceSize < 32) {
            feedback?.invoke("❌ Source JAR too small: $sourcePath ($sourceSize bytes)")
            logger.error("Source JAR too small: $sourcePath ($sourceSize bytes)")
            throw IOException("Source JAR too small: $sourcePath")
        }

        // Validate source JAR integrity and count entries
        var sourceEntryCount = 0
        JarFile(sourcePath.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { jar ->
            sourceEntryCount = jar.entries().asSequence().count { !it.isDirectory }
            if (sourceEntryCount == 0) {
                feedback?.invoke("❌ Source JAR has no non-directory entries: $sourcePath")
                logger.error("Source JAR has no non-directory entries: $sourcePath")
                throw IOException("Source JAR has no non-directory entries: $sourcePath")
            }
            jar.entries().asSequence().take(10).forEach { entry ->
                if (!entry.isDirectory) {
                    jar.getInputStream(entry).use { input ->
                        try {
                            input.readAllBytes()
                        } catch (e: IOException) {
                            feedback?.invoke("❌ Invalid entry ${entry.name} in $sourcePath: ${e.message}")
                            logger.error("Invalid entry ${entry.name} in $sourcePath", e)
                            throw e
                        }
                    }
                }
            }
        }
        feedback?.invoke("✅ Source JAR valid: $sourcePath ($sourceSize bytes, $sourceEntryCount entries)")
        logger.debug("Source JAR valid: $sourcePath ($sourceSize bytes, $sourceEntryCount entries)")

        if (dryRun) {
            feedback?.invoke("🧪 [dry-run] Would extend $sourcePath with ${newEntry.name}")
            logger.debug("[dry-run] Would extend JAR")
            targetPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(targetPath, "// Dry-run extended JAR")
            return@catch
        }

        ensureDirectoryWritable(targetPath.parent, "target JAR directory").getOrElse { throw it as Throwable }

        val tempJar = Files.createTempFile(targetPath.parent, "zilla.extend.temp", ".jar")
        var lockAcquired = false
        var retries = maxRetries

        FileChannel.open(tempJar, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { fileChannel ->
            while (retries > 0 && !lockAcquired) {
                try {
                    fileChannel.lock()
                    lockAcquired = true
                    feedback?.invoke("🔒 Acquired write lock on $tempJar")
                    logger.debug("Acquired write lock on $tempJar")
                } catch (e: OverlappingFileLockException) {
                    feedback?.invoke("⚠️ Cannot lock $tempJar, retrying ($retries retries left): ${e.message}")
                    logger.warn("Cannot lock $tempJar, retrying: ${e.message}", e)
                    Thread.sleep(retryDelay)
                    retries--
                }
            }
            if (!lockAcquired) {
                feedback?.invoke("❌ Failed to acquire lock on $tempJar after retries")
                logger.error("Failed to acquire lock on $tempJar")
                throw IOException("Failed to acquire lock on $tempJar")
            }

            JarOutputStream(Channels.newOutputStream(fileChannel)).use { targetJarOut ->
                JarFile(sourcePath.toFile(), true, ZipFile.OPEN_READ, JarFile.runtimeVersion()).use { sourceJar ->
                    sourceJar.entries().asSequence().forEach { entry ->
                        if (entry.name == newEntry.name) {
                            feedback?.invoke("⚠️ Skipping existing entry ${entry.name} in target JAR")
                            logger.debug("Skipping existing entry ${entry.name}")
                            return@forEach
                        }
                        targetJarOut.putNextEntry(JarEntry(entry.name).apply {
                            time = entry.time
                            size = entry.size
                            compressedSize = entry.compressedSize
                            crc = entry.crc
                            method = entry.method
                            extra = entry.extra
                            comment = entry.comment
                        })
                        sourceJar.getInputStream(entry).copyTo(targetJarOut)
                        targetJarOut.closeEntry()
                    }
                    // Add new entry
                    targetJarOut.putNextEntry(newEntry)
                    Files.newInputStream(newEntryPath).copyTo(targetJarOut)
                    targetJarOut.closeEntry()
                }
            }
        }

        Files.move(tempJar, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        feedback?.invoke("✅ Extended JAR to $targetPath")
        logger.debug("Extended JAR to $targetPath")
    }.mapLeft {
        feedback?.invoke("❌ Failed to extend JAR: ${it.message}")
        logger.error("Failed to extend JAR", it)
        ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error")
    }

    private fun ensureDirectoryWritable(dir: Path?, description: String): Either<ZpmResolutionErrorKt, Unit> = Either.catch {
        if (dir != null) {
            Files.createDirectories(dir)
            if (!Files.isWritable(dir)) {
                throw IOException("$description is not writable: $dir")
            }
        }
    }.mapLeft {
        ZpmResolutionErrorKt.DependencyResolutionError(it.message ?: "Unknown error")
    }
}