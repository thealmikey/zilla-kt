package io.aklivity.zilla.manager.internal.commands.install.model

import java.nio.file.Path

data class PromotedModule(
    val name:String,
    val path:Path
)