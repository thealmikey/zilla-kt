package io.aklivity.zilla.manager.internal.commands.install.model

import kotlinx.serialization.Serializable

@Serializable
data class ZpmTemplate(
    val repositories: List<String> = emptyList(),
    val imports: List<String> = emptyList(),
    val dependencies: List<String> = emptyList()
)