package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import org.junit.Test
import to.bitkit.models.BitcoinDisplayUnit
import kotlin.test.assertEquals

class BitcoinVisualTransformationTest {

    @Test
    fun `modern filter strips non-digits from pasted input`() {
        val result = BitcoinVisualTransformation(BitcoinDisplayUnit.MODERN)
            .filter(AnnotatedString("1000087188..........,,,,,"))

        assertEquals("1 000 087 188", result.text.text)
    }

    @Test
    fun `classic filter keeps single decimal separator only`() {
        val result = BitcoinVisualTransformation(BitcoinDisplayUnit.CLASSIC)
            .filter(AnnotatedString("1,23.4.5"))

        assertEquals("123.45", result.text.text)
    }
}
