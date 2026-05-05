package to.bitkit.ui.utils.visualTransformation

import androidx.compose.ui.text.AnnotatedString
import org.junit.Before
import org.junit.Test
import to.bitkit.models.BitcoinDisplayUnit
import java.util.Locale
import kotlin.test.assertEquals

class BitcoinVisualTransformationTest {

    @Before
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

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

    @Test
    fun `modern filter cursor mapping handles leading zeros`() {
        val result = BitcoinVisualTransformation(BitcoinDisplayUnit.MODERN)
            .filter(AnnotatedString("01000"))

        assertEquals("1 000", result.text.text)

        val mapping = result.offsetMapping
        assertEquals(0, mapping.originalToTransformed(0))
        // Raw offset 1 (after the stripped leading '0') should still land at the
        // start of "1 000", not past the displayed '1'.
        assertEquals(0, mapping.originalToTransformed(1))
        assertEquals(5, mapping.originalToTransformed(5))
        // Transformed offset 1 (after the '1') maps back to raw offset 2.
        assertEquals(2, mapping.transformedToOriginal(1))
    }

    @Test
    fun `modern filter cursor mapping handles multiple leading zeros`() {
        val result = BitcoinVisualTransformation(BitcoinDisplayUnit.MODERN)
            .filter(AnnotatedString("00100"))

        assertEquals("100", result.text.text)

        val mapping = result.offsetMapping
        assertEquals(0, mapping.originalToTransformed(0))
        assertEquals(0, mapping.originalToTransformed(1))
        assertEquals(0, mapping.originalToTransformed(2))
        assertEquals(1, mapping.originalToTransformed(3))
        assertEquals(3, mapping.originalToTransformed(5))
    }
}
