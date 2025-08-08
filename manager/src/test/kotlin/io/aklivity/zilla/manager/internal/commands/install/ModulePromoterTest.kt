package io.aklivity.zilla.manager.internal.commands.install

import io.aklivity.zilla.manager.internal.commands.install.impl.DefaultModulePromoter
import io.aklivity.zilla.manager.internal.commands.install.model.PromotedModule
import java.nio.file.*
import kotlin.test.*
import io.aklivity.zilla.manager.internal.commands.install.ZpmError.PromotionFailed
import arrow.core.Either

class ModulePromoterTest {

    @Test
    fun `should promote when valid jar`() {
        val dir = Files.createTempDirectory("zpm-test")
        val jar = dir.resolve("example.jar")
        Files.createFile(jar)

        val promoter = DefaultModulePromoter()
        val result = promoter.promote(jar)

        assertTrue(result.isRight(), "Expected promotion to succeed")
        val promoted = result.getOrNull()
        assertEquals("example", promoted?.name)
        assertEquals(jar, promoted?.path)
    }

    @Test
    fun `should return error when jar does not exist`() {
        val jar = Paths.get("nonexistent-file-xyz.jar")
        val promoter = DefaultModulePromoter()

        val result = promoter.promote(jar)

        assertTrue(result.isLeft(), "Expected failure for nonexistent file")
        val error = result.swap().getOrNull()
        assertIs<PromotionFailed>(error)
        assertTrue((error as PromotionFailed).reason.contains("not found"))
    }

    @Test
    fun `should provide feedback during promotion`() {
        val dir = Files.createTempDirectory("zpm-test")
        val jar = dir.resolve("feedback-test.jar")
        Files.createFile(jar)

        val events = mutableListOf<String>()
        val promoter = DefaultModulePromoter(feedback = { events.add(it) })

        val result = promoter.promote(jar)

        assertTrue(result.isRight())
        assertTrue(events.any { it.contains("➡️ Starting promotion") })
        assertTrue(events.any { it.contains("📦 Inferred module name") })
        assertTrue(events.any { it.contains("✅ Promoted:") })
    }

    @Test
    fun `should simulate promotion in dry run mode`() {
        val dir = Files.createTempDirectory("zpm-test")
        val jar = dir.resolve("dryrun.jar")
        Files.createFile(jar)

        val events = mutableListOf<String>()
        val promoter = DefaultModulePromoter(dryRun = true, feedback = { events.add(it) })

        val result = promoter.promote(jar)

        assertTrue(result.isRight())
        assertTrue(events.any { it.contains("[dry-run] Would promote") })
    }

    @Test
    fun `should handle unexpected exceptions with rich error`() {
        val promoter = object : DefaultModulePromoter() {
            override fun promote(jar: Path): Either<ZpmError, PromotedModule> {
                return Either.catch {
                    throw RuntimeException("Boom!")
                }.mapLeft {
                    ZpmError.PromotionFailed("💥 Boom error: ${it.message}")
                }
            }
        }

        val result = promoter.promote(Paths.get("any.jar"))
        assertTrue(result.isLeft())
        val error = result.swap().getOrNull()
        assertTrue(error is PromotionFailed)
        assertTrue((error as PromotionFailed).reason.contains("Boom"))
    }
}
