package to.bitkit.ext

import android.icu.text.MessageFormat

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


/**
 * Pluralizes this string using the ICU MessageFormat with the provided arguments map.
 *
 * @param argMap A map of arguments to be formatted into the string for pluralization.
 */
fun String.formatPlural(argMap: Map<Any, Any>): String {
    val messageFormat = MessageFormat(this)
    return messageFormat.format(argMap)
}
