package io.aklivity.zilla.manager.internal.commands.install.impl

import arrow.core.*
import java.nio.file.*
import io.aklivity.zilla.manager.internal.commands.install.*
import io.aklivity.zilla.manager.internal.commands.install.model.PromotedModule

open class DefaultModulePromoter(
     val dryRun: Boolean = false,
     val feedback: ((String) -> Unit)? = null
) : ModulePromoter {

    override fun promote(jar: Path): Either<ZpmError, PromotedModule> {
        feedback?.invoke("➡️ Starting promotion for: $jar")

        if (!Files.exists(jar)) {
            val msg = "❌ JAR file not found: $jar"
            feedback?.invoke(msg)
            return ZpmError.PromotionFailed(msg).left()
        }

        if (!Files.isReadable(jar)) {
            val msg = "❌ JAR file not readable: $jar"
            feedback?.invoke(msg)
            return ZpmError.PromotionFailed(msg).left()
        }

        return Either.catch {
            val name = inferModuleName(jar)
            feedback?.invoke("📦 Inferred module name: $name")

            if (dryRun) {
                feedback?.invoke("✅ [dry-run] Would promote: $name from $jar")
            } else {
                feedback?.invoke("✅ Promoted: $name from $jar")
            }

            PromotedModule(name = name, path = jar)
        }.mapLeft { error ->
            val msg = "💥 Failed to promote $jar: ${error.message}"
            feedback?.invoke(msg)
            ZpmError.PromotionFailed(msg)
        }
    }

    private fun inferModuleName(jar: Path): String {
        return jar.fileName.toString().removeSuffix(".jar")
    }
}
