package io.aklivity.zilla.manager.internal

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

object ZpmMainKt {
    @JvmStatic
    fun main(args: Array<String>) {
        ZillaManager()
            .subcommands(WrapCommand(), InstallCommand(), CleanCommand())
            .main(args)
    }
}