package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.SATS_GROUPING_SEPARATOR
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class BitcoinVisualTransformation(
    private val displayUnit: BitcoinDisplayUnit
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = sanitizeInput(text.text)

        if (originalText.isEmpty()) {
            return TransformedText(AnnotatedString(""), OffsetMapping.Identity)
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

    private fun sanitizeInput(text: String): String = when (displayUnit) {
        BitcoinDisplayUnit.MODERN -> text.filter { it.isDigit() }
        BitcoinDisplayUnit.CLASSIC -> sanitizeClassicInput(text)
    }

    private fun sanitizeClassicInput(text: String): String {
        val filtered = text.filter { it.isDigit() || it == '.' }
        val dotIndex = filtered.indexOf('.')
        if (dotIndex == -1) {
            return filtered
        }
        return filtered.substring(0, dotIndex + 1) +
            filtered.substring(dotIndex + 1).replace(".", "")
    }

    private fun formatModernDisplay(text: String): String {
        val digits = text.replace("$SATS_GROUPING_SEPARATOR", "")
        if (digits.isEmpty()) {
            return ""
        }
        val normalizedDigits = digits.trimStart('0').ifEmpty { "0" }
        return normalizedDigits.reversed().chunked(3).joinToString(" ").reversed()
    }

    private fun formatClassicDisplay(text: String): String {
        val cleanText = text.replace(" ", "").replace(",", "")
        if (cleanText.isEmpty() || cleanText == ".") {
            return cleanText
        }

        val endsWithDecimal = cleanText.endsWith(".")
        val textToFormat = if (endsWithDecimal) cleanText.dropLast(1) else cleanText
        if (textToFormat.isEmpty()) {
            return cleanText
        }

        val doubleValue = textToFormat.toDoubleOrNull() ?: return cleanText

        val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
            groupingSeparator = ' '
            decimalSeparator = '.'
        }
        val formatter = DecimalFormat("#,##0.########", formatSymbols)
        val formatted = formatter.format(doubleValue)
        return if (endsWithDecimal) "$formatted." else formatted
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
