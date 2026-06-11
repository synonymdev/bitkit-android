package to.bitkit.models

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.ext.ellipsisMiddle
import com.synonym.bitkitcore.PubkyProfile as CorePubkyProfile

@Immutable
data class PubkyProfileLink(val label: String, val url: String)

@Stable
data class PubkyProfile(
    val publicKey: String,
    val name: String,
    val bio: String,
    val imageUrl: String?,
    val links: List<PubkyProfileLink>,
    val tags: List<String> = emptyList(),
    val status: String?,
) {
    companion object {
        private const val TRUNCATED_PK_LENGTH = 11

        fun fromFfi(publicKey: String, ffiProfile: CorePubkyProfile): PubkyProfile {
            return PubkyProfile(
                publicKey = publicKey,
                name = ffiProfile.name,
                bio = ffiProfile.bio ?: "",
                imageUrl = ffiProfile.image,
                links = ffiProfile.links.orEmpty().map { PubkyProfileLink(label = it.title, url = it.url) },
                tags = emptyList(),
                status = ffiProfile.status,
            )
        }

        fun placeholder(publicKey: String) = PubkyProfile(
            publicKey = publicKey,
            name = publicKey.ellipsisMiddle(TRUNCATED_PK_LENGTH),
            bio = "",
            imageUrl = null,
            links = emptyList(),
            tags = emptyList(),
            status = null,
        )

        fun forDisplay(
            publicKey: String,
            name: String?,
            imageUrl: String?,
        ) = PubkyProfile(
            publicKey = publicKey,
            name = name ?: publicKey.ellipsisMiddle(TRUNCATED_PK_LENGTH),
            bio = "",
            imageUrl = imageUrl,
            links = emptyList(),
            tags = emptyList(),
            status = null,
        )
    }

    val truncatedPublicKey: String
        get() = publicKey.ellipsisMiddle(TRUNCATED_PK_LENGTH)

    fun toProfileData() = PubkyProfileData(
        name = name,
        bio = bio,
        image = imageUrl,
        links = links.map { PubkyProfileDataLink(label = it.label, url = it.url) },
        tags = tags,
    )
}

@Serializable
data class PubkyProfileDataLink(val label: String, val url: String)

@Serializable
data class PubkyProfileData(
    val name: String,
    val bio: String,
    val image: String? = null,
    val links: List<PubkyProfileDataLink> = emptyList(),
    val tags: List<String> = emptyList(),
) {
    companion object {
        fun decode(json: String): PubkyProfileData =
            Json { ignoreUnknownKeys = true }.decodeFromString(json)
    }

    fun encode(): ByteArray =
        Json.encodeToString(this).toByteArray(Charsets.UTF_8)

    fun toPubkyProfile(publicKey: String) = PubkyProfile(
        publicKey = publicKey,
        name = name,
        bio = bio,
        imageUrl = image,
        links = links.map { PubkyProfileLink(label = it.label, url = it.url) },
        tags = tags,
        status = null,
    )
}

@Serializable
data class HomegateResponse(
    val signupCode: String,
    val homeserverPubky: String,
)
