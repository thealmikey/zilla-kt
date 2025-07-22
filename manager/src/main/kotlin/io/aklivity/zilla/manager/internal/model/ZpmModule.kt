package io.aklivity.zilla.manager.internal.commands.install.model

import java.nio.file.Path

data class ZpmModule(val name: String, val paths: List<Path>)