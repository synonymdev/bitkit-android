package to.bitkit.ext

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
