package to.bitkit.models

import androidx.compose.runtime.Immutable

@Immutable
data class PubkyAuthPermission(
    val path: String,
    val accessLevel: String,
) {
    val displayAccess: String
        get() = accessLevel.map { char ->
            when (char) {
                'r' -> "READ"
                'w' -> "WRITE"
                else -> ""
            }
        }.filter { it.isNotEmpty() }.joinToString(", ")
}

data class PubkyAuthRequest(
    val rawUrl: String,
    val relay: String,
    val permissions: List<PubkyAuthPermission>,
    val serviceNames: List<String>,
) {
    companion object {
        fun parseCapabilities(caps: String): List<PubkyAuthPermission> =
            caps.split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { segment ->
                    val lastColon = segment.lastIndexOf(':')
                    if (lastColon <= 0) return@mapNotNull null
                    val path = segment.substring(0, lastColon)
                    val access = segment.substring(lastColon + 1)
                    PubkyAuthPermission(path = path, accessLevel = access)
                }

        fun extractServiceName(path: String): String? {
            val parts = path.trimStart('/').split("/")
            val pubIndex = parts.indexOf("pub")
            return if (pubIndex >= 0 && pubIndex + 1 < parts.size) parts[pubIndex + 1] else null
        }
    }
}
