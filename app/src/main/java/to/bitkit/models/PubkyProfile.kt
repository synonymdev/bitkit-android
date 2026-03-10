package to.bitkit.models

import com.synonym.paykit.FfiProfile

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
        fun fromFfi(publicKey: String, ffiProfile: FfiProfile): PubkyProfile {
            return PubkyProfile(
                publicKey = publicKey,
                name = ffiProfile.name,
                bio = ffiProfile.bio ?: "",
                imageUrl = ffiProfile.image,
                links = ffiProfile.links.orEmpty().map { PubkyProfileLink(label = it.title, url = it.url) },
                status = ffiProfile.status,
            )
        }
    }

    val truncatedPublicKey: String
        get() = if (publicKey.length > 10) {
            "${publicKey.take(4)}...${publicKey.takeLast(4)}"
        } else {
            publicKey
        }
}
