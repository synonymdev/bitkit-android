package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

class SatsVisualTransformation : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text

        if (originalText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val transformedText = formatSats(originalText)

        return TransformedText(
            AnnotatedString(transformedText),
            createOffsetMapping(originalText, transformedText)
        )
    }

    private fun formatSats(text: String): String {
        // Remove any existing spaces and non-numeric characters
        val cleanText = text.filter { it.isDigit() }

        if (cleanText.isEmpty()) return ""

        // Add spaces every 3 digits from the right (thousands separator)
        return cleanText.reversed()
            .chunked(3)
            .joinToString(" ")
            .reversed()
    }

    private fun createOffsetMapping(original: String, transformed: String): OffsetMapping {
        return object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= original.length) return transformed.length

                // Count digits before the offset in original
                val digitsBeforeOffset = original.take(offset).count { it.isDigit() }

                // Calculate how many spaces should be added
                val spacesBeforeOffset = maxOf(0, (digitsBeforeOffset - 1) / 3)

                return minOf(digitsBeforeOffset + spacesBeforeOffset, transformed.length)
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 0) return 0
                if (offset >= transformed.length) return original.length

                // Count digits before the offset in transformed (ignoring spaces)
                val digitsBeforeOffset = transformed.take(offset).count { it.isDigit() }

                return minOf(digitsBeforeOffset, original.length)
            }
        }
    }
}
