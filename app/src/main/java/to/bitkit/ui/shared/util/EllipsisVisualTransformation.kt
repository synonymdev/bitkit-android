package to.bitkit.ui.shared.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

@Composable
fun ellipsisVisualTransformation(
    maxLength: Int,
    ellipsis: String = "…",
) = VisualTransformation {
    val text = if (it.length > maxLength) {
        buildAnnotatedString {
            append(it.take(maxLength - ellipsis.length))
            append(ellipsis)
        }
    } else it
    TransformedText(text, OffsetMapping.Identity)
}
