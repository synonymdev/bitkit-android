package to.bitkit.ui.settings.backups

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfirmMnemonicSelectionTest {
    private val original = listOf("alpha", "bravo", "charlie", "delta")
    private val shuffled = listOf("charlie", "alpha", "delta", "bravo")

    private fun tap(stack: List<Int>, index: Int) = reduceMnemonicSelection(stack, index, shuffled, original)

    @Test
    fun `pushes correct words in order`() {
        val stack = listOf(1, 3, 0, 2).fold(emptyList<Int>()) { acc, index -> tap(acc, index) }

        assertEquals(listOf(1, 3, 0, 2), stack)
        assertTrue(isMnemonicSelectionComplete(stack, shuffled, original))
    }

    @Test
    fun `wrong word is removed by tapping its chip again`() {
        val stack = tap(emptyList(), 0)

        assertEquals(listOf(0), stack)
        assertEquals(emptyList(), tap(stack, 0))
    }

    @Test
    fun `wrong word is removed by tapping the red word`() {
        val stack = tap(listOf(1), 2)

        assertEquals(listOf(1), tap(stack, stack.last()))
    }

    @Test
    fun `tapping another chip after a wrong word does nothing`() {
        val stack = tap(emptyList(), 0)

        assertEquals(listOf(0), tap(stack, 1))
        assertEquals(listOf(0), tap(stack, 3))
    }

    @Test
    fun `wrong last word can be removed when all slots are filled`() {
        val shuffled = listOf("charlie", "alpha", "echo", "bravo", "delta")
        val stack = listOf(1, 3, 0, 2)

        assertFalse(isMnemonicSelectionComplete(stack, shuffled, original))
        assertEquals(stack, reduceMnemonicSelection(stack, 4, shuffled, original))
        assertEquals(listOf(1, 3, 0), reduceMnemonicSelection(stack, 2, shuffled, original))
    }

    @Test
    fun `correct word cannot be removed`() {
        val stack = listOf(1, 3)

        assertEquals(stack, tap(stack, 3))
        assertEquals(stack, tap(stack, 1))
    }

    @Test
    fun `full correct selection ignores further taps`() {
        val stack = listOf(1, 3, 0, 2)

        assertEquals(stack, tap(stack, 2))
        assertEquals(stack, tap(stack, 0))
    }

    @Test
    fun `duplicate words are tracked per chip`() {
        val original = listOf("alpha", "bravo", "alpha")
        val shuffled = listOf("alpha", "bravo", "alpha")

        var stack = reduceMnemonicSelection(emptyList(), 2, shuffled, original)
        assertEquals(listOf(2), stack)

        stack = reduceMnemonicSelection(stack, 2, shuffled, original)
        assertEquals(listOf(2), stack)

        stack = reduceMnemonicSelection(stack, 1, shuffled, original)
        stack = reduceMnemonicSelection(stack, 0, shuffled, original)
        assertEquals(listOf(2, 1, 0), stack)
        assertTrue(isMnemonicSelectionComplete(stack, shuffled, original))
    }

    @Test
    fun `out of range tap does nothing`() {
        assertEquals(emptyList(), tap(emptyList(), shuffled.size))
        assertEquals(emptyList(), tap(emptyList(), -1))
    }
}
