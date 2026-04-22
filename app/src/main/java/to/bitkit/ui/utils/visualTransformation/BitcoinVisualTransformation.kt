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
        val rawText = text.text
        val sanitizedText = sanitizeInput(rawText)

        if (sanitizedText.isEmpty()) {
            return TransformedText(AnnotatedString(""), OffsetMapping.Identity)
        }

        val formattedText = when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> formatModernDisplay(sanitizedText)
            BitcoinDisplayUnit.CLASSIC -> formatClassicDisplay(sanitizedText)
        }

        return TransformedText(
            AnnotatedString(formattedText),
            createOffsetMapping(rawText, formattedText),
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

    private fun createOffsetMapping(rawOriginal: String, transformed: String): OffsetMapping {
        val rawToSanitizedCount = IntArray(rawOriginal.length + 1)
        var dotSeen = false
        var sanitizedSoFar = 0
        for (i in rawOriginal.indices) {
            val char = rawOriginal[i]
            val isKept = when {
                displayUnit == BitcoinDisplayUnit.MODERN -> char.isDigit()
                char.isDigit() -> true
                char == '.' && !dotSeen -> {
                    dotSeen = true
                    true
                }
                else -> false
            }
            if (isKept) sanitizedSoFar++
            rawToSanitizedCount[i + 1] = sanitizedSoFar
        }
        val totalSanitized = sanitizedSoFar

        return object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val clamped = offset.coerceIn(0, rawOriginal.length)
                val validCount = rawToSanitizedCount[clamped]
                if (validCount >= totalSanitized) return transformed.length
                var transformedOffset = 0
                var counted = 0
                while (transformedOffset < transformed.length && counted < validCount) {
                    if (transformed[transformedOffset] != ' ') counted++
                    transformedOffset++
                }
                while (transformedOffset < transformed.length && transformed[transformedOffset] == ' ') {
                    transformedOffset++
                }
                return transformedOffset
            }

            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, transformed.length)
                if (clamped >= transformed.length) return rawOriginal.length
                val validCount = transformed.take(clamped).count { it != ' ' }
                for (i in 0..rawOriginal.length) {
                    if (rawToSanitizedCount[i] >= validCount) return i
                }
                return rawOriginal.length
            }
        }
    }
}
