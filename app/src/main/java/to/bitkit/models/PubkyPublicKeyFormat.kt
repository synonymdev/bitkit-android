package to.bitkit.models

import to.bitkit.ext.ellipsisMiddle
import java.util.Locale

object PubkyPublicKeyFormat {
    private const val pubkyPrefix = "pubky"
    private const val rawKeyLength = 52
    private const val redactedLength = 16
    const val maximumInputLength = 57

    private val zBase32Regex = Regex("^[ybndrfg8ejkmcpqxot1uwisza345h769]+$")

    fun bounded(input: String): String {
        return input
            .trim()
            .lowercase(Locale.US)
            .take(maximumInputLength)
    }

    fun normalized(input: String): String? {
        val normalizedInput = input.trim().lowercase(Locale.US)
        val rawKey = normalizedInput.removePrefix(pubkyPrefix)

        if (rawKey.length != rawKeyLength || !zBase32Regex.matches(rawKey)) {
            return null
        }

        return "$pubkyPrefix$rawKey"
    }

    fun matches(lhs: String?, rhs: String?): Boolean {
        val normalizedLhs = lhs?.let(::normalized) ?: return false
        val normalizedRhs = rhs?.let(::normalized) ?: return false
        return normalizedLhs == normalizedRhs
    }

    fun redacted(input: String): String {
        val normalizedInput = normalized(input) ?: input.trim()
        return normalizedInput.ellipsisMiddle(redactedLength)
    }
}
