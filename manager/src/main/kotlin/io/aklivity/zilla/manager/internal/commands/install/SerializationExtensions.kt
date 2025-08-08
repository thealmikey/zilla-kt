package io.aklivity.zilla.manager.internal.commands.install

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Convenience helpers for reading/writing JSON to files using kotlinx.serialization.
 *
 * Note: serializer<T>() used in reified inline form is marked experimental, so we opt-in here.
 */

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Json.decodeFromFile(path: Path): T {
    val text = path.readText()
    return this.decodeFromString(serializer(), text)
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> Json.encodeToFile(path: Path, value: T, pretty: Boolean = false) {
    val jsonText = if (pretty) this.encodeToString(serializer(), value)
    else this.encodeToString(serializer(), value)
    path.parent?.let { Files.createDirectories(it) }
    path.writeText(jsonText)
}
