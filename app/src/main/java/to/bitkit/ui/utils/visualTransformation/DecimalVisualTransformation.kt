package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

class DecimalVisualTransformation(
    private val decimalPlaces: Int = 2,
    private val isSatsInput: Boolean = false // If true, converts sats to BTC
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text

        if (originalText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val transformedText = formatDecimal(originalText)

        return TransformedText(
            AnnotatedString(transformedText),
            createOffsetMapping(originalText, transformedText)
        )
    }

    private fun formatDecimal(text: String): String {
        if (text.isEmpty()) return ""

        try {
            // Allow decimal point and digits
            val cleanText = text.filter { it.isDigit() || it == '.' }

            if (cleanText.isEmpty()) return ""

            val value = if (isSatsInput && !cleanText.contains('.')) {
                // If it's sats input and no decimal point, convert sats to BTC
                val satsValue = cleanText.toLongOrNull() ?: 0L
                satsValue / 100_000_000.0
            } else {
                // Otherwise treat as decimal input
                cleanText.toDoubleOrNull() ?: 0.0
            }

            // Create formatter with specified decimal places
            val symbols = DecimalFormatSymbols(Locale.US)
            val pattern = "0." + "0".repeat(decimalPlaces)
            val formatter = DecimalFormat(pattern, symbols)

            return formatter.format(value)
        } catch (e: Exception) {
            return text // Return original text if formatting fails
        }
    }

    private fun createOffsetMapping(original: String, transformed: String): OffsetMapping {
        return object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= original.length) return transformed.length

                // Handle decimal point mapping
                if (original.contains('.') && transformed.contains('.')) {
                    val originalDecimalIndex = original.indexOf('.')
                    val transformedDecimalIndex = transformed.indexOf('.')

                    if (offset <= originalDecimalIndex) {
                        // Before decimal point - try to maintain relative position
                        val ratio = if (originalDecimalIndex > 0) {
                            transformedDecimalIndex.toDouble() / originalDecimalIndex.toDouble()
                        } else 1.0
                        return minOf((offset * ratio).toInt(), transformedDecimalIndex)
                    } else {
                        // After decimal point
                        val offsetAfterDecimal = offset - originalDecimalIndex - 1
                        return minOf(transformedDecimalIndex + 1 + offsetAfterDecimal, transformed.length)
                    }
                } else if (!original.contains('.') && transformed.contains('.')) {
                    // Original has no decimal but transformed does (sats conversion case)
                    val transformedDecimalIndex = transformed.indexOf('.')
                    if (offset < original.length) {
                        // Map to before decimal in transformed
                        val ratio = if (original.length > 0) {
                            transformedDecimalIndex.toDouble() / original.length.toDouble()
                        } else 1.0
                        return minOf((offset * ratio).toInt(), transformedDecimalIndex)
                    } else {
                        return transformed.length
                    }
                } else {
                    // No decimal handling needed
                    val ratio = if (original.length > 0) {
                        transformed.length.toDouble() / original.length.toDouble()
                    } else 1.0
                    return minOf((offset * ratio).toInt(), transformed.length)
                }
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= transformed.length) return original.length

                // Handle decimal point mapping
                if (original.contains('.') && transformed.contains('.')) {
                    val originalDecimalIndex = original.indexOf('.')
                    val transformedDecimalIndex = transformed.indexOf('.')

                    if (offset <= transformedDecimalIndex) {
                        // Before decimal point
                        val ratio = if (transformedDecimalIndex > 0) {
                            originalDecimalIndex.toDouble() / transformedDecimalIndex.toDouble()
                        } else 1.0
                        return minOf((offset * ratio).toInt(), originalDecimalIndex)
                    } else {
                        // After decimal point
                        val offsetAfterDecimal = offset - transformedDecimalIndex - 1
                        return minOf(originalDecimalIndex + 1 + offsetAfterDecimal, original.length)
                    }
                } else if (!original.contains('.') && transformed.contains('.')) {
                    // Original has no decimal but transformed does (sats conversion case)
                    val transformedDecimalIndex = transformed.indexOf('.')
                    if (offset <= transformedDecimalIndex) {
                        // Map from before decimal in transformed to original
                        val ratio = if (transformedDecimalIndex > 0) {
                            original.length.toDouble() / transformedDecimalIndex.toDouble()
                        } else 1.0
                        return minOf((offset * ratio).toInt(), original.length)
                    } else {
                        return original.length
                    }
                } else {
                    // No decimal handling needed
                    val ratio = if (transformed.length > 0) {
                        original.length.toDouble() / transformed.length.toDouble()
                    } else 1.0
                    return minOf((offset * ratio).toInt(), original.length)
                }
            }
        }
    }
}
