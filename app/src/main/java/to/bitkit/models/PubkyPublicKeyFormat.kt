package to.bitkit.models

import com.synonym.paykit.PaykitPublicKeys
import to.bitkit.ext.ellipsisMiddle
import java.util.Locale

object PubkyPublicKeyFormat {
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
