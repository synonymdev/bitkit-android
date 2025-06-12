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

        val transformedText = when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> formatSats(originalText)
            BitcoinDisplayUnit.CLASSIC -> formatBtc(originalText)
        }

        return TransformedText(
            AnnotatedString(transformedText),
            createOffsetMapping(originalText, transformedText)
        )
    }

    private fun formatSats(text: String): String {
        // Remove any existing spaces and non-numeric characters except the first character if it's a digit
        val cleanText = text.filter { it.isDigit() }

        if (cleanText.isEmpty()) return ""

        // Add spaces every 3 digits from the right (thousands separator)
        return cleanText.reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
    }

    private fun formatBtc(text: String): String {
        if (text.isEmpty()) return ""

        try {
            // Allow decimal point and digits
            val cleanText = text.filter { it.isDigit() || it == '.' }

            if (cleanText.isEmpty()) return ""

            // If it contains a decimal point, treat as BTC input
            if (cleanText.contains('.')) {
                val btcValue = cleanText.toDoubleOrNull() ?: 0.0
                val symbols = DecimalFormatSymbols(Locale.US)
                val formatter = DecimalFormat("0.00000000", symbols)
                return formatter.format(btcValue)
            } else {
                // If no decimal point, treat as sats and convert to BTC for display
                val satsValue = cleanText.toLongOrNull() ?: 0L
                val btcValue = satsValue / 100_000_000.0
                val symbols = DecimalFormatSymbols(Locale.US)
                val formatter = DecimalFormat("0.00000000", symbols)
                return formatter.format(btcValue)
            }
        } catch (e: Exception) {
            return text // Return original text if formatting fails
        }
    }

    private fun createOffsetMapping(original: String, transformed: String): OffsetMapping {
        return object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= original.length) return transformed.length

                // For MODERN (sats with spaces), we need to account for added spaces
                if (displayUnit == BitcoinDisplayUnit.MODERN) {
                    val digitsBeforeOffset = original.take(offset).count { it.isDigit() }
                    val spacesBeforeOffset = (digitsBeforeOffset - 1) / 3
                    return minOf(digitsBeforeOffset + spacesBeforeOffset, transformed.length)
                }

                // For CLASSIC (BTC decimals), the transformation is more complex
                // We'll map to the end of the transformed string for simplicity
                return transformed.length
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= transformed.length) return original.length

                // For MODERN (sats with spaces), we need to account for spaces
                if (displayUnit == BitcoinDisplayUnit.MODERN) {
                    val digitsBeforeOffset = transformed.take(offset).count { it.isDigit() }
                    return minOf(digitsBeforeOffset, original.length)
                }

                // For CLASSIC (BTC decimals), map to the end of original
                return original.length
            }
        }
    }
}
