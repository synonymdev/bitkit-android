package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import to.bitkit.models.BitcoinDisplayUnit
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class BitcoinVisualTransformation(
    private val displayUnit: BitcoinDisplayUnit
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text

        if (originalText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val formattedText = when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> formatModernDisplay(originalText)
            BitcoinDisplayUnit.CLASSIC -> formatClassicDisplay(originalText)
        }

        val offsetMapping = createOffsetMapping(originalText, formattedText)

        return TransformedText(
            AnnotatedString(formattedText),
            offsetMapping
        )
    }

    private fun formatModernDisplay(text: String): String {
        val cleanText = text.replace(" ", "")
        val longValue = cleanText.toLongOrNull() ?: return text

        val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
            groupingSeparator = ' '
        }
        val formatter = DecimalFormat("#,###", formatSymbols).apply {
            isGroupingUsed = true
        }
        return formatter.format(longValue)
    }

    private fun formatClassicDisplay(text: String): String {
        val cleanText = text.replace(" ", "").replace(",", "")
        val doubleValue = cleanText.toDoubleOrNull() ?: return text

        val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
            groupingSeparator = ' '
            decimalSeparator = '.'
        }
        val formatter = DecimalFormat("#,##0.########", formatSymbols)
        return formatter.format(doubleValue)
    }

    private fun createOffsetMapping(original: String, transformed: String): OffsetMapping {
        return object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val cleanOriginal = original.take(offset).replace(" ", "")
                var transformedOffset = 0
                var cleanOffset = 0

                for (char in transformed) {
                    if (char == ' ') {
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
                val cleanCount = transformedSubstring.count { it != ' ' }

                var originalOffset = 0
                var cleanOffset = 0

                for (char in original) {
                    if (char != ' ') {
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
