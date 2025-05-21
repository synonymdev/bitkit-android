package to.bitkit.ui.shared

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import to.bitkit.ui.theme.Colors

@Composable
fun InfoField(
    value: String,
    label: String? = null,
    maxLength: Int? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    onValueChange: (String) -> Unit = {},
) {
    OutlinedTextField(
        label = { label?.let { Text(it) } },
        value = value,
        onValueChange = onValueChange,
        trailingIcon = trailingIcon,
        enabled = false,
        readOnly = true,
        singleLine = true,
        colors = Colors.White.let {
            OutlinedTextFieldDefaults.colors(
                disabledTextColor = it,
                disabledBorderColor = Colors.White10,
                disabledLabelColor = it,
                disabledTrailingIconColor = it,
            )
        },
        textStyle = MaterialTheme.typography.labelSmall,
        visualTransformation = maxLength?.let { ellipsisVisualTransformation(it) } ?: VisualTransformation.None,
        modifier = Modifier.fillMaxWidth()
    )
}

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
