package to.bitkit.ui.utils

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.withStyle
import to.bitkit.ui.theme.Colors

fun String.withAccent(
    defaultColor: Color = Color.Unspecified,
    accentColor: Color = Colors.Brand,
    accentStyle: SpanStyle = SpanStyle(color = accentColor),
): AnnotatedString {
    val regex = Regex("<accent>(.*?)</accent>", RegexOption.DOT_MATCHES_ALL)
    val matches = regex.findAll(this)

    return buildAnnotatedString {
        var lastIndex = 0

        matches.forEach { match ->
            val startIndex = match.range.first
            val endIndex = match.range.last + 1

            // Add text before the match with default color
            if (startIndex > lastIndex) {
                withStyle(style = SpanStyle(color = defaultColor)) {
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
            withStyle(style = SpanStyle(color = defaultColor)) {
                append(this@withAccent.substring(lastIndex))
            }
        }
    }
}

fun String.withAccentLink(url: String): AnnotatedString {
    val htmlText = this
        .replace("<accent>", "<a href=\"$url\">")
        .replace("</accent>", "</a>")

    return AnnotatedString.fromHtml(
        htmlString = htmlText,
        linkStyles = TextLinkStyles(style = SpanStyle(color = Colors.Brand)),
    )
}

fun String.withBold(
    defaultColor: Color = Color.Unspecified,
    boldStyle: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold),
    defaultStyle: SpanStyle = SpanStyle(color = defaultColor),
): AnnotatedString {
    val regex = Regex("<bold>(.*?)</bold>", RegexOption.DOT_MATCHES_ALL)
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

@Composable
@ReadOnlyComposable
fun localizedRandom(@StringRes id: Int): String {
    val resources = LocalContext.current.resources
    val localizedString = resources.getString(id)
    val parts = localizedString.split("\n")

    return if (parts.size > 1) {
        parts.random()
    } else {
        localizedString
    }
}
