package to.bitkit.models

import to.bitkit.ext.ellipsisMiddle
import com.synonym.bitkitcore.PubkyProfile as CorePubkyProfile

data class PubkyProfileLink(val label: String, val url: String)

data class PubkyProfile(
    val publicKey: String,
    val name: String,
    val bio: String,
    val imageUrl: String?,
    val links: List<PubkyProfileLink>,
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
                status = ffiProfile.status,
            )
        }

        fun placeholder(publicKey: String) = PubkyProfile(
            publicKey = publicKey,
            name = publicKey.ellipsisMiddle(TRUNCATED_PK_LENGTH),
            bio = "",
            imageUrl = null,
            links = emptyList(),
            status = null,
        )
    }

    val truncatedPublicKey: String
        get() = publicKey.ellipsisMiddle(TRUNCATED_PK_LENGTH)
}
