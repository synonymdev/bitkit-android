package to.bitkit.models

import android.net.Uri

object PubkyContactLink {
    /** Z-base-32 characters ordered by their five-bit values. */
    private const val zBase32Alphabet = "ybndrfg8ejkmcpqxot1uwisza345h769"

    /** Mask for the only data bit in the final symbol of a 32-byte key. */
    private const val zBase32FinalSymbolDataMask = 0b10000

    fun matches(uri: Uri): Boolean =
        uri.scheme.equals("bitkit", ignoreCase = true) && uri.host.equals("contact", ignoreCase = true)

    fun publicKey(uri: Uri): String? {
        if (!matches(uri)) return null
        if (!uri.encodedAuthority.equals("contact", ignoreCase = true) ||
            !uri.path.isNullOrEmpty() || uri.fragment != null
        ) {
            return null
        }
        if (uri.queryParameterNames != setOf("pubky") || uri.encodedQuery.orEmpty().contains('&')) return null
        val key = uri.getQueryParameters("pubky").singleOrNull()
            ?.takeIf { it.length <= PubkyPublicKeyFormat.maximumInputLength } ?: return null

        return PubkyPublicKeyFormat.normalized(key)?.let(::canonicalize)
    }

    private fun canonicalize(publicKey: String): String {
        val lastCharacterValue = zBase32Alphabet.indexOf(publicKey.last())
        val canonicalLastCharacter = zBase32Alphabet[lastCharacterValue and zBase32FinalSymbolDataMask]
        return publicKey.dropLast(1) + canonicalLastCharacter
    }
}
