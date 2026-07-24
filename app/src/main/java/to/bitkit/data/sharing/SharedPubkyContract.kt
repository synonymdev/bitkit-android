package to.bitkit.data.sharing

import android.net.Uri
import kotlinx.serialization.Serializable
import to.bitkit.utils.AppError
import java.util.Locale

object SharedPubkyContract {
    const val PROTOCOL_VERSION = 1
    const val BITKIT_SOURCE = "to.bitkit"
    const val RING_SOURCE = "app.pubkyring"
    const val RING_AUTHORITY = "app.pubkyring.sharedpubky"
    const val RING_READ_PERMISSION = "app.pubkyring.permission.READ_SHARED_PUBKY"
    const val IDENTITIES_PATH = "v1/identities"
    const val RING_IDENTITIES_URI = "content://$RING_AUTHORITY/$IDENTITIES_PATH"

    const val COLUMN_PROTOCOL_VERSION = "protocol_version"
    const val COLUMN_SOURCE_PACKAGE = "source_package"
    const val COLUMN_PUBKY = "pubky"
    const val COLUMN_SECRET_KEY = "secret_key"

    private const val BITKIT_PUBKY_PREFIX = "pubky"
    private const val WIRE_PUBKY_LENGTH = 52
    private const val CREDENTIAL_SEGMENT = "credential"
    private val wirePubkyPattern = Regex("^[ybndrfg8ejkmcpqxot1uwisza345h769]{52}$")
    private val secretKeyPattern = Regex("^[0-9a-f]{64}$")

    val publicColumns = arrayOf(
        COLUMN_PROTOCOL_VERSION,
        COLUMN_SOURCE_PACKAGE,
        COLUMN_PUBKY,
    )
    val credentialColumns = publicColumns + COLUMN_SECRET_KEY

    val ringIdentitiesUri: Uri
        get() = Uri.parse(RING_IDENTITIES_URI)

    fun ringCredentialUri(pubky: String): Uri = Uri.parse(ringCredentialUriString(pubky))

    internal fun ringCredentialUriString(pubky: String): String =
        "$RING_IDENTITIES_URI/${canonicalPubky(pubky)}/$CREDENTIAL_SEGMENT"

    fun canonicalPubky(value: String): String {
        val normalizedPubky = value.trim().lowercase(Locale.US)
        val barePubky = if (
            normalizedPubky.length == WIRE_PUBKY_LENGTH + BITKIT_PUBKY_PREFIX.length &&
            normalizedPubky.startsWith(BITKIT_PUBKY_PREFIX)
        ) {
            normalizedPubky.removePrefix(BITKIT_PUBKY_PREFIX)
        } else {
            normalizedPubky
        }
        require(wirePubkyPattern.matches(barePubky)) { "Invalid shared Pubky public key" }
        return barePubky
    }

    fun requireWirePubky(value: String): String {
        require(wirePubkyPattern.matches(value)) { "Invalid shared Pubky wire public key" }
        return value
    }

    fun toBitkitPubky(value: String): String = "$BITKIT_PUBKY_PREFIX${canonicalPubky(value)}"

    fun canonicalSecretKeyHex(value: String): String {
        require(secretKeyPattern.matches(value)) { "Invalid shared Pubky secret key" }
        return value
    }
}

data class SharedPubkyIdentity(
    val protocolVersion: Int,
    val sourcePackage: String,
    val pubky: String,
)

class SharedPubkyCredential(
    val identity: SharedPubkyIdentity,
    secretKeyHex: String,
) {
    val secretKeyHex = SharedPubkyContract.canonicalSecretKeyHex(secretKeyHex)
}

@Serializable
data class ExternalPubkyIdentityRef(
    val protocolVersion: Int,
    val sourcePackage: String,
    val pubky: String,
) {
    fun validated(): ExternalPubkyIdentityRef {
        if (protocolVersion != SharedPubkyContract.PROTOCOL_VERSION) {
            throw SharedPubkyError.UnsupportedVersion(protocolVersion)
        }
        if (sourcePackage != SharedPubkyContract.RING_SOURCE) {
            throw SharedPubkyError.UntrustedSource(sourcePackage)
        }
        return copy(pubky = SharedPubkyContract.requireWirePubky(pubky))
    }
}

sealed class SharedPubkyError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    data object SourceUnavailable : SharedPubkyError("Pubky Ring identity sharing is unavailable")
    class UntrustedSource(source: String) : SharedPubkyError("Untrusted Pubky identity source '$source'")
    class UnsupportedVersion(version: Int) : SharedPubkyError("Unsupported Pubky sharing version '$version'")
    data object InvalidResponse : SharedPubkyError("Pubky Ring returned an invalid shared identity")
    data object IdentityUnavailable : SharedPubkyError("The selected Pubky Ring identity is unavailable")
    data object IdentityConflict : SharedPubkyError("Another Pubky profile is already connected")
}
