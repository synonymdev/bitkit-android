package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import to.bitkit.models.BTC_PLACEHOLDER
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.SATS_IN_BTC
import to.bitkit.utils.Logger
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
        // Remove any existing formatting
        val cleanText = text.filter { it.isDigit() }

        if (cleanText.isEmpty()) return ""

        try {
            val satsValue = cleanText.toLongOrNull() ?: 0L

            // Convert sats to BTC (1 BTC = 100,000,000 sats)
            val btcValue = satsValue / SATS_IN_BTC

            // Format with 8 decimal places
            val symbols = DecimalFormatSymbols(Locale.US)
            val formatter = DecimalFormat(BTC_PLACEHOLDER, symbols)

            return formatter.format(btcValue)
        } catch (e: Exception) {
            Logger.error("formatBTC error: ", e = e, context = "BitcoinVisualTransformation")
            return BTC_PLACEHOLDER
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
