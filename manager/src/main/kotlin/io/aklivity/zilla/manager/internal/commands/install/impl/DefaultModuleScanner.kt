package io.aklivity.zilla.manager.internal.commands.install.impl

import io.aklivity.zilla.manager.internal.commands.install.ModuleScanner
import java.nio.file.*
import arrow.core.*
import io.aklivity.zilla.manager.internal.commands.install.*
import io.aklivity.zilla.manager.internal.commands.install.model.PromotedModule

class DefaultModuleScanner(
    private val dryRun: Boolean = false,
    private val feedback: ((String) -> Unit)? = null
) : ModuleScanner {

    override fun scan(path: Path): Either<ZpmError, List<Path>> {
        feedback?.invoke("📂 Scanning path: $path")

        if (!Files.exists(path)) {
            val msg = "❌ Directory does not exist: $path"
            feedback?.invoke(msg)
            return ZpmError.ModuleScanFailed(Exception(msg)).left()
        }

        if (!Files.isDirectory(path)) {
            val msg = "❌ Path is not a directory: $path"
            feedback?.invoke(msg)
            return ZpmError.ModuleScanFailed(Exception(msg)).left()
        }

        return if (dryRun) {
            feedback?.invoke("✅ [dry-run] Would scan: $path and collect .jar files")
            emptyList<Path>().right() // Simulate success
        } else {
            Either.catch {
                Files.walk(path)
                    .filter { it.toString().endsWith(".jar") }
                    .toList()
            }.map {
                feedback?.invoke("✅ Found ${it.size} .jar file(s)")
                it
            }.mapLeft { error ->
                val msg = "💥 Failed to scan path $path: ${error.message}"
                feedback?.invoke(msg)
                ZpmError.ModuleScanFailed(error)
            }
        }
    }
}
