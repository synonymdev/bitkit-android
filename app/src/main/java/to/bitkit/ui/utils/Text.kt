package to.bitkit.ui.utils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import to.bitkit.ui.theme.Colors

fun String.withAccent(
    color: Color = Color.Unspecified,
    accent: Color = Colors.Brand,
    accentStyle: SpanStyle = SpanStyle(color = accent),
): AnnotatedString {
    val regex = Regex("<accent>(.*?)</accent>")
    val matches = regex.findAll(this)

    return buildAnnotatedString {
        var lastIndex = 0

        matches.forEach { match ->
            val startIndex = match.range.first
            val endIndex = match.range.last + 1

            // Add text before the match with default color
            if (startIndex > lastIndex) {
                withStyle(style = SpanStyle(color = color)) {
                    append(this@withAccent.substring(lastIndex, startIndex))
                }
            }

            // Add the matched text with accent color
            withStyle(style = accentStyle) {
                append(match.groups[1]?.value ?: "")
            }

            lastIndex = endIndex
        }

        // Add remaining text after the last match with default color
        if (lastIndex < this@withAccent.length) {
            withStyle(style = SpanStyle(color = color)) {
                append(this@withAccent.substring(lastIndex))
            }
        }
    }
}

fun String.withBold(
    color: Color = Color.Unspecified,
    boldStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold),
    defaultStyle: SpanStyle = SpanStyle(color = color),
): AnnotatedString {
    val regex = Regex("<bold>(.*?)</bold>")
    val matches = regex.findAll(this)

    return buildAnnotatedString {
        var lastIndex = 0

        matches.forEach { match ->
            val startIndex = match.range.first
            val endIndex = match.range.last + 1

            // Add text before the match with the default style
            if (startIndex > lastIndex) {
                withStyle(style = defaultStyle) {
                    append(this@withBold.substring(lastIndex, startIndex))
                }
            }

            // Add the matched text with the bold style
            withStyle(style = boldStyle) {
                append(match.groups[1]?.value ?: "")
            }

            lastIndex = endIndex
        }

        // Add remaining text after the last match with the default style
        if (lastIndex < this@withBold.length) {
            withStyle(style = defaultStyle) {
                append(this@withBold.substring(lastIndex))
            }
        }
    }
}

data class TranslationPart(
    val text: String,
    val isAccent: Boolean
)

fun String.splitIntoParts(): List<TranslationPart> {
    val string = this
    val parts = mutableListOf<TranslationPart>()
    var currentIndex = 0
    val (tagStart, tagEnd) = "<accent>" to "</accent>"

    while (currentIndex < string.length) {
        val startRange = string.indexOf(tagStart, currentIndex)

        if (startRange != -1) {
            // Add non-accented text before the tag if any
            if (currentIndex < startRange) {
                val text = string.substring(currentIndex, startRange)
                parts.add(TranslationPart(text = text, isAccent = false))
            }

            // Find the end of the accented text
            val endRange = string.indexOf(tagEnd, startIndex = startRange + tagStart.length)
            if (endRange != -1) {
                val text = string.substring(startIndex = startRange + tagStart.length, endRange)
                parts.add(TranslationPart(text = text, isAccent = true))
                currentIndex = endRange + tagEnd.length
            } else {
                // Malformed string, no closing tag
                break
            }
        } else {
            // No more accent tags, add remaining text
            val text = string.substring(currentIndex)
            parts.add(TranslationPart(text = text, isAccent = false))
            break
        }
    }

    return parts
}
