package to.bitkit.models

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.ext.pubkyDisplayPublicKey
import com.synonym.paykit.PaykitProfile as SdkPaykitProfile
import com.synonym.paykit.PubkyProfile as SdkPubkyProfile

private val pubkyProfileJson = Json { ignoreUnknownKeys = true }

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

        fun fromPubkyProfile(publicKey: String, sdkProfile: SdkPubkyProfile): PubkyProfile {
            return PubkyProfile(
                publicKey = publicKey,
                name = sdkProfile.name,
                bio = sdkProfile.bio ?: "",
                imageUrl = sdkProfile.image,
                links = sdkProfile.links.map { PubkyProfileLink(label = it.title, url = it.url) },
                tags = emptyList(),
                status = sdkProfile.status,
            )
        }

        fun fromPaykitProfile(publicKey: String, profile: SdkPaykitProfile): PubkyProfile =
            PubkyProfileData.fromPaykitProfile(profile).toPubkyProfile(publicKey)

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
        get() = publicKey.pubkyDisplayPublicKey()

    fun withNameFallback(fallbackName: String?): PubkyProfile {
        return if (name.isBlank() && !fallbackName.isNullOrBlank()) copy(name = fallbackName) else this
    }

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
            pubkyProfileJson.decodeFromString(json)

        fun fromPaykitProfile(profile: SdkPaykitProfile): PubkyProfileData {
            val extra = profile.extraJson?.let { runCatching { decode(it) }.getOrNull() }
            return PubkyProfileData(
                name = profile.displayName ?: extra?.name.orEmpty(),
                bio = extra?.bio.orEmpty(),
                image = profile.imageUri ?: extra?.image,
                links = extra?.links.orEmpty(),
                tags = extra?.tags.orEmpty(),
            )
        }
    }

    fun encode(): ByteArray =
        pubkyProfileJson.encodeToString(this).toByteArray(Charsets.UTF_8)

    fun toPaykitProfile() = SdkPaykitProfile(
        displayName = name,
        imageUri = image,
        extraJson = encode().toString(Charsets.UTF_8),
    )

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
