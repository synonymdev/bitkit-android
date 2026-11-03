package to.bitkit.models

import com.synonym.paykit.PaykitPublicKeys
import to.bitkit.ext.ellipsisMiddle
import java.util.Locale

object PubkyPublicKeyFormat {
    /** Z-base-32 characters ordered by their five-bit values. */
    private const val zBase32Alphabet = "ybndrfg8ejkmcpqxot1uwisza345h769"

    /** Mask for the only data bit in the final symbol of a 32-byte key. */
    private const val zBase32FinalSymbolDataMask = 0b10000

    private const val displayEdgeLength = 4
    private const val redactedLength = 16
    const val maximumInputLength = 57

    fun bounded(input: String): String {
        return input
            .trim()
            .lowercase(Locale.US)
            .take(maximumInputLength)
    }

    fun normalized(input: String): String? {
        return runCatching { PaykitPublicKeys.normalize(bounded(input)) }.getOrNull()
    }

    fun canonicalized(input: String): String? {
        val publicKey = normalized(input) ?: return null
        val lastCharacterValue = zBase32Alphabet.indexOf(publicKey.last())
        val canonicalLastCharacter = zBase32Alphabet[lastCharacterValue and zBase32FinalSymbolDataMask]
        return publicKey.dropLast(1) + canonicalLastCharacter
    }

    fun matches(lhs: String?, rhs: String?): Boolean {
        val normalizedLhs = lhs?.let(::normalized) ?: return false
        val normalizedRhs = rhs?.let(::normalized) ?: return false
        return normalizedLhs == normalizedRhs
    }

    fun display(input: String): String {
        val rawKey = bounded(input).removePrefix("pubky")
        return if (rawKey.length > displayEdgeLength * 2) {
            "${rawKey.take(displayEdgeLength)}...${rawKey.takeLast(displayEdgeLength)}"
        } else {
            rawKey
        }
    }

    fun redacted(input: String): String {
        val normalizedInput = normalized(input) ?: input.trim()
        return normalizedInput.ellipsisMiddle(redactedLength)
    }
}
