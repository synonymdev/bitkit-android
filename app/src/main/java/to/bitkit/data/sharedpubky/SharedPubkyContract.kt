package to.bitkit.data.sharedpubky

import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitSdkService

private const val PUBKY_PREFIX = "pubky"
private val SECRET_KEY_PATTERN = Regex("[0-9a-f]{64}")

object SharedPubkyContract {
    const val AUTHORITY_SUFFIX = ".sharedpubky"
    const val PATH_IDENTITIES = "v1/identities"
    const val PATH_CREDENTIAL = "credential"
    const val COLUMN_PUBKY = "pubky"
    const val COLUMN_SECRET_KEY = "secret_key"
    const val RING_PACKAGE = "app.pubkyring"
    const val RING_AUTHORITY = RING_PACKAGE + AUTHORITY_SUFFIX
    const val RING_PERMISSION = "$RING_PACKAGE.permission.READ_SHARED_PUBKY"

    fun isValidSecret(
        secretKeyHex: String,
        pubky: String,
        derivePublicKey: (String) -> String = PaykitSdkService::publicKeyFromSecret,
    ): Boolean = PubkyPublicKeyFormat.matches(pubkyFromSecret(secretKeyHex, derivePublicKey), pubky)

    internal fun pubkyFromSecret(
        secretKeyHex: String,
        derivePublicKey: (String) -> String,
    ): String? {
        if (!SECRET_KEY_PATTERN.matches(secretKeyHex)) return null
        return runCatching { derivePublicKey(secretKeyHex) }
            .getOrNull()
            ?.let(PubkyPublicKeyFormat::normalized)
            ?.removePrefix(PUBKY_PREFIX)
    }
}
