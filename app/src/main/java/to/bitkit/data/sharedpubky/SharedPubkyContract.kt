package to.bitkit.data.sharedpubky

import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.services.PaykitSdkService

private const val PUBKY_PREFIX = "pubky"
private val SECRET_KEY_PATTERN = Regex("[0-9a-f]{64}")

object SharedPubkyContract {
    /** Appended to an applicationId to form its shared pubky provider authority, same as in Pubky Ring. */
    const val AUTHORITY_SUFFIX = ".sharedpubky"

    /** Provider path listing the app's pubkys, one [COLUMN_PUBKY] row each. */
    const val PATH_IDENTITIES = "v1/identities"

    /** Final segment of `v1/identities/<pubky>/credential`, which returns that pubky's secret key. */
    const val PATH_CREDENTIAL = "credential"

    /** Public key column of both the identities and credential rows. */
    const val COLUMN_PUBKY = "pubky"

    /** Secret key column of the credential row, as 64 lowercase hex characters. */
    const val COLUMN_SECRET_KEY = "secret_key"

    /** Pubky Ring's Android applicationId. */
    const val RING_PACKAGE = "app.pubkyring"

    /** Authority of Pubky Ring's shared pubky provider. */
    const val RING_AUTHORITY = RING_PACKAGE + AUTHORITY_SUFFIX

    /** Prefix of the stored `app.pubkyring:<pubky>` source reference that marks an adopted Ring pubky. */
    const val RING_SOURCE_PREFIX = "$RING_PACKAGE:"

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
