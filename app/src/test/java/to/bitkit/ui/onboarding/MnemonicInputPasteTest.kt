package to.bitkit.ui.onboarding

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MnemonicInputPasteTest {

    @Test
    fun `space typed inside a word should not count as a paste`() {
        val previous = TextFieldValue("abandon", TextRange(4))
        val new = TextFieldValue("aban don", TextRange(5))

        assertFalse(isPastedInput(previous = previous, new = new))
    }

    @Test
    fun `space typed after a word should not count as a paste`() {
        val previous = TextFieldValue("abandon", TextRange(7))
        val new = TextFieldValue("abandon ", TextRange(8))

        assertFalse(isPastedInput(previous = previous, new = new))
    }

    @Test
    fun `fragment pasted into an empty field should count as a paste`() {
        val previous = TextFieldValue()
        val new = TextFieldValue("abandon ability able", TextRange(20))

        assertTrue(isPastedInput(previous = previous, new = new))
    }

    @Test
    fun `fragment pasted over a selected word should count as a paste`() {
        val previous = TextFieldValue("abandon", TextRange(0, 7))
        val new = TextFieldValue("ab cd", TextRange(5))

        assertTrue(isPastedInput(previous = previous, new = new))
    }

    @Test
    fun `fragment pasted into an empty field should be forwarded whole`() {
        val previous = TextFieldValue()
        val new = TextFieldValue("abandon ability able", TextRange(20))

        assertEquals("abandon ability able", insertedText(previous = previous, new = new))
    }

    @Test
    fun `fragment pasted after a filled word should drop that word`() {
        val previous = TextFieldValue("about", TextRange(5))
        val new = TextFieldValue("aboutabout abandon art", TextRange(22))

        assertEquals("about abandon art", insertedText(previous = previous, new = new))
    }

    @Test
    fun `fragment pasted before a filled word should drop that word`() {
        val previous = TextFieldValue("about", TextRange(0))
        val new = TextFieldValue("abandon artabout", TextRange(11))

        assertEquals("abandon art", insertedText(previous = previous, new = new))
    }

    @Test
    fun `fragment pasted over a selected word should drop that word`() {
        val previous = TextFieldValue("abandon", TextRange(0, 7))
        val new = TextFieldValue("about art", TextRange(9))

        assertEquals("about art", insertedText(previous = previous, new = new))
    }
}
