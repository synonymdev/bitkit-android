package to.bitkit.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents the root of the JSON object.
 */
@Serializable
data class ReleaseInfoDTO(
    val platforms: Platforms,
)

@Serializable
data class Platforms(
    val android: PlatformDetails,
    val ios: PlatformDetails? = null,
)

/**
 * Holds the specific version information for a single platform.
 */
@Serializable
data class PlatformDetails(
    val version: String,

    val buildNumber: Int,

    val notes: String,

    @SerialName("pub_date")
    val pubDate: String,

    val url: String,

    @SerialName("critical")
    val isCritical: Boolean,
)
