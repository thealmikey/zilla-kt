package io.aklivity.zilla.manager.internal.commands.install.cache

data class ZpmArtifactIdKt(
    val groupId: String,
    val artifactId: String,
    val version: String
) {
    companion object {
        fun parse(coordinate: String): ZpmArtifactIdKt {
            val parts = coordinate.split(":")
            require(parts.size == 3) {
                "Invalid artifact coordinate '$coordinate'. Expected format: groupId:artifactId:version"
            }
            return ZpmArtifactIdKt(parts[0], parts[1], parts[2])
        }
    }

    override fun toString(): String = "$groupId:$artifactId:$version"
}
