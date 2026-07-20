package to.bitkit.ext

import android.icu.text.MessageFormat
import to.bitkit.utils.Logger

private const val TAG = "StringExt"

fun String.ellipsisMiddle(totalLength: Int): String {
    return when {
        this.length > totalLength -> {
            val halfLength = (totalLength - 1) / 2
            "${this.take(halfLength)}…${this.takeLast(halfLength)}"
        }
        else -> this
    }
}

fun String.pubkyDisplayPublicKey(): String {
    val rawKey = removePrefix("pubky")
    return if (rawKey.length > 8) "${rawKey.take(4)}...${rawKey.takeLast(4)}" else rawKey
}

fun String.truncate(length: Int): String {
    return if (this.length > length) {
        "${this.substring(0, length - 3)}..."
    } else {
        this
    }.trim()
}

fun String.removeSpaces() = this.filterNot { it.isWhitespace() }

fun String.spaceToNewline() = replace(" ", "\n")

fun String.toLongOrDefault(defaultValue: Long = 0): Long = toLongOrNull() ?: defaultValue

/**
 * Pluralizes this string using the ICU MessageFormat with the provided arguments map.
 *
 * @param argMap A map of arguments to be formatted into the string for pluralization.
 */
fun String.formatPlural(argMap: Map<Any, Any>): String =
    runCatching { MessageFormat(this).format(argMap) }
        .getOrElse {
            Logger.warn("Failed to format plural pattern '$this'", it, context = TAG)
            this
        }
