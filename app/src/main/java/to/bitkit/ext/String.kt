package to.bitkit.ext

import android.icu.text.MessageFormat
import to.bitkit.env.Defaults
import to.bitkit.utils.Logger

private const val TAG = "StringExt"

private val LINE_BREAK_REGEX = Regex("\\r\\n|[\\n\\u000B\\u000C\\r\\u0085\\u2028\\u2029]")

fun String.ellipsisMiddle(totalLength: Int): String {
    return when {
        this.length > totalLength -> {
            val halfLength = (totalLength - 1) / 2
            "${this.take(halfLength)}…${this.takeLast(halfLength)}"
        }
        else -> this
    }
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

fun String.sanitizeTag(): String = replace(LINE_BREAK_REGEX, " ")
    .take(Defaults.TAG_MAX_LENGTH)
    .dropDanglingSurrogate()

private fun String.dropDanglingSurrogate(): String =
    if (isNotEmpty() && last().isHighSurrogate()) dropLast(1) else this

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
