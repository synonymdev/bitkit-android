package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import to.bitkit.models.BitcoinDisplayUnit

class BitcoinVisualTransformation(
    private val displayUnit: BitcoinDisplayUnit
) : VisualTransformation {

    private val satsTransformation = SatsVisualTransformation()
    private val decimalTransformation = DecimalVisualTransformation()

    override fun filter(text: AnnotatedString): TransformedText {
        return when (displayUnit) {
            BitcoinDisplayUnit.MODERN -> satsTransformation.filter(text)
            BitcoinDisplayUnit.CLASSIC -> decimalTransformation.filter(text)
        }
    }
}
