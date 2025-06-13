package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class MonetaryVisualTransformation(
    private val decimalPlaces: Int = 2
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text

        if (originalText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val formattedText = formatMonetaryValue(originalText)
        val offsetMapping = createOffsetMapping(originalText, formattedText)

        return TransformedText(
            AnnotatedString(formattedText),
            offsetMapping
        )
    }

    private fun formatMonetaryValue(text: String): String {
        val cleanText = text.replace(",", "").replace(" ", "")
        val doubleValue = cleanText.toDoubleOrNull() ?: return text

        val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
            groupingSeparator = ','
            decimalSeparator = '.'
        }

        val decimalPlacesPattern = "#".repeat(decimalPlaces)
        val formatter = DecimalFormat("#,##0.$decimalPlacesPattern", formatSymbols).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = decimalPlaces
        }

        return formatter.format(doubleValue)
    }

    private fun createOffsetMapping(original: String, transformed: String): OffsetMapping {
        return object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val cleanOriginal = original.take(offset).replace(",", "").replace(" ", "")
                var transformedOffset = 0
                var cleanOffset = 0

                for (char in transformed) {
                    if (char == ',' || char == ' ') {
                        transformedOffset++
                    } else {
                        if (cleanOffset >= cleanOriginal.length) break
                        cleanOffset++
                        transformedOffset++
                    }
                }

                return transformedOffset.coerceAtMost(transformed.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                val transformedSubstring = transformed.take(offset)
                val cleanCount = transformedSubstring.count { it != ',' && it != ' ' }

                var originalOffset = 0
                var cleanOffset = 0

                for (char in original) {
                    if (char != ',' && char != ' ') {
                        if (cleanOffset >= cleanCount) break
                        cleanOffset++
                    }
                    originalOffset++
                }

                return originalOffset.coerceAtMost(original.length)
            }
        }
    }
}
