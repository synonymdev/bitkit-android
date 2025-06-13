package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.text.iterator

class MonetaryVisualTransformation(
    private val decimalPlaces: Int = 2
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text

        if (originalText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        // Limit decimal places before formatting
        val limitedText = limitDecimalPlaces(originalText)
        val formattedText = formatMonetaryValue(limitedText)
        val offsetMapping = createOffsetMapping(limitedText, formattedText)

        return TransformedText(
            AnnotatedString(formattedText),
            offsetMapping
        )
    }

    private fun limitDecimalPlaces(text: String): String {
        val cleanText = text.replace(",", "").replace(" ", "")

        val decimalIndex = cleanText.indexOf('.')
        if (decimalIndex == -1) {
            return cleanText
        }

        val integerPart = cleanText.substring(0, decimalIndex)
        val decimalPart = cleanText.substring(decimalIndex + 1)

        // Limit decimal part to specified places
        val limitedDecimalPart = decimalPart.take(decimalPlaces)

        return if (limitedDecimalPart.isEmpty() && cleanText.endsWith(".")) {
            "$integerPart."
        } else if (limitedDecimalPart.isEmpty()) {
            integerPart
        } else {
            "$integerPart.$limitedDecimalPart"
        }
    }

    private fun formatMonetaryValue(text: String): String {
        // Handle cases where user is typing a decimal point
        if (text.isEmpty() || text == ".") {
            return text
        }

        // If text ends with a decimal point, preserve it
        val endsWithDecimal = text.endsWith(".")
        val textToFormat = if (endsWithDecimal) text.dropLast(1) else text

        // If the text to format is empty after removing the decimal, return original
        if (textToFormat.isEmpty()) {
            return text
        }

        val doubleValue = textToFormat.toDoubleOrNull() ?: return text

        val formatSymbols = DecimalFormatSymbols(Locale.getDefault()).apply {
            groupingSeparator = ','
            decimalSeparator = '.'
        }

        // Only format the integer part if user is typing a decimal
        val formatter = if (endsWithDecimal) {
            DecimalFormat("#,##0", formatSymbols)
        } else {
            val decimalPlacesPattern = "#".repeat(decimalPlaces)
            DecimalFormat("#,##0.$decimalPlacesPattern", formatSymbols).apply {
                minimumFractionDigits = 0
                maximumFractionDigits = decimalPlaces
            }
        }

        val formatted = formatter.format(doubleValue)
        return if (endsWithDecimal) "$formatted." else formatted
    }

    private fun createOffsetMapping(original: String, transformed: String): OffsetMapping {
        return object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= original.length) return transformed.length

                val originalSubstring = original.take(offset)
                var transformedOffset = 0
                var originalIndex = 0

                for (char in transformed) {
                    if (originalIndex >= originalSubstring.length) break

                    if (char == ',') {
                        // Skip comma in transformed, don't advance original
                        transformedOffset++
                    } else if (originalIndex < originalSubstring.length &&
                        originalSubstring[originalIndex] == char) {
                        // Characters match, advance both
                        originalIndex++
                        transformedOffset++
                    } else {
                        // Look for next matching character in original
                        var found = false
                        for (i in originalIndex until originalSubstring.length) {
                            if (originalSubstring[i] == char) {
                                originalIndex = i + 1
                                transformedOffset++
                                found = true
                                break
                            }
                        }
                        if (!found) break
                    }
                }

                return transformedOffset.coerceAtMost(transformed.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= transformed.length) return original.length

                val transformedSubstring = transformed.take(offset)
                var originalOffset = 0
                var transformedIndex = 0

                for (char in original) {
                    if (transformedIndex >= transformedSubstring.length) break

                    if (char == transformedSubstring[transformedIndex]) {
                        // Characters match, advance both
                        transformedIndex++
                        originalOffset++
                    } else if (transformedIndex < transformedSubstring.length - 1 &&
                        transformedSubstring[transformedIndex] == ',') {
                        // Skip comma in transformed
                        transformedIndex++
                        if (transformedIndex < transformedSubstring.length &&
                            char == transformedSubstring[transformedIndex]) {
                            transformedIndex++
                            originalOffset++
                        }
                    } else {
                        originalOffset++
                    }
                }

                return originalOffset.coerceAtMost(original.length)
            }
        }
    }
}
