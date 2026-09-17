package to.bitkit.ui.onboarding

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
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
}
