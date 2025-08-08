package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.Option
import arrow.core.Some
import kotlin.test.*

class ZpmDomainModelTest {

    @Test
    fun `should format ZpmDependency correctly`() {
        val dep = ZpmDependencyKt("com.example", "lib", Some("1.0.0"))
        assertEquals("com.example:lib:1.0.0", dep.toString())
    }

    @Test
    fun `should construct ZpmArtifactId correctly`() {
        val id = ZpmArtifactIdKt("org.foo", "bar", "2.3.4")
        assertEquals("org.foo", id.groupId)
        assertEquals("bar", id.artifactId)
        assertEquals("2.3.4", id.version)
    }
}
