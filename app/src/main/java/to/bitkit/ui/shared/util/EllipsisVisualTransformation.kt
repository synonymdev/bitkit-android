package to.bitkit.ui.shared.util

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

fun ellipsisVisualTransformation(
    maxLength: Int,
    ellipsis: String = "…",
) = VisualTransformation { originalText ->
    val transformedText = if (originalText.length > maxLength) buildAnnotatedString {
        append(originalText.take(maxLength - ellipsis.length))
        append(ellipsis)
    }
    else originalText

    val oldLen = originalText.length
    val newLen = transformedText.length

    val offsetMapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = if (offset <= maxLength - ellipsis.length) offset else newLen

        override fun transformedToOriginal(offset: Int) = if (offset <= maxLength - ellipsis.length) offset else oldLen
    }

    TransformedText(transformedText, offsetMapping)
}
