package io.aklivity.zilla.manager.internal.commands.install.cache

import arrow.core.getOrElse
import arrow.core.None
import arrow.core.Option
import arrow.core.Some

data class ZpmDependencyKt(
    val groupId: String,
    val artifactId: String,
    val version: Option<String> = None
) {
    companion object {
        /**
         * Accepts:
         *  - group:artifact:version  -> Some(version)
         *  - group:artifact          -> None (latest)
         */
        fun fromCoordinates(coordinate: String): ZpmDependencyKt? {
            val parts = coordinate.split(":")
            return when (parts.size) {
                3 -> ZpmDependencyKt(parts[0], parts[1], Some(parts[2]))
                2 -> ZpmDependencyKt(parts[0], parts[1], None)
                else -> null
            }
        }
    }

    fun toCoordinateString(): String =
        "${groupId}:${artifactId}:${version.fold({ "LATEST" }, { it })}"
}
