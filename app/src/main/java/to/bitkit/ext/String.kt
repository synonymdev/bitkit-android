package to.bitkit.ext

/**
 * Trims the string to show a specified number of characters from both ends,
 * separated by an ellipsis if the string is longer than twice the specified size.
 *
 * @param len The number of characters to display from each end of the string.
 * @return The original string if it is shorter than or equal to `size * 2`;
 *         otherwise, returns the formatted string with ends and ellipsis.
 */
fun String.takeEnds(len: Int): String {
    return when (this.length > len * 2) {
        true -> "${this.take(len)}…${this.takeLast(len)}"
        else -> this
    }
}
