package to.bitkit.ui.components

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression pins for #633: long recovery phrase words must stay on one line.
 *
 * Widths are fake pixels: each character is as wide as the font size value, so a word fits when
 * `length * fontSize <= budget`.
 */
class MnemonicWordsGridTest {

    private val measureWord: (String, TextUnit) -> Int = { word, fontSize -> (word.length * fontSize.value).toInt() }

    @Test
    fun `short words keep the full font size`() {
        val result = fitMnemonicFontSize(
            words = listOf("cat", "dog", "sun"),
            wordBudgetPx = { 100 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(17.sp, fits = true), result)
    }

    @Test
    fun `empty words keep the full font size`() {
        val result = fitMnemonicFontSize(
            words = emptyList(),
            wordBudgetPx = { 0 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(17.sp, fits = true), result)
    }

    @Test
    fun `longest word picks the largest step that fits for the whole grid`() {
        val result = fitMnemonicFontSize(
            words = listOf("cat", "abstract", "dog"),
            wordBudgetPx = { 110 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(13.5.sp, fits = true), result)
    }

    @Test
    fun `wider two digit labels shrink the budget of later words`() {
        val words = List(12) { if (it == 11) "research" else "cat" }

        val result = fitMnemonicFontSize(
            words = words,
            wordBudgetPx = { number -> if (number >= 10) 104 else 136 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(13.sp, fits = true), result)
    }

    @Test
    fun `word that never fits falls back to the minimum font size and wrapping`() {
        val result = fitMnemonicFontSize(
            words = listOf("category"),
            wordBudgetPx = { 10 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(12.sp, fits = false), result)
    }

    @Test
    fun `word that fits exactly at the minimum font size does not wrap`() {
        val result = fitMnemonicFontSize(
            words = listOf("category"),
            wordBudgetPx = { 96 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(12.sp, fits = true), result)
    }

    @Test
    fun `word one pixel too wide at the minimum font size wraps`() {
        val result = fitMnemonicFontSize(
            words = listOf("cat", "category"),
            wordBudgetPx = { 95 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(12.sp, fits = false), result)
    }

    @Test
    fun `actual words needing a smaller size set the shared size`() {
        val result = fitSharedMnemonicFontSize(
            wordLists = listOf(listOf("mushroom"), listOf("secret")),
            wordBudgetPx = { 110 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(13.5.sp, fits = true), result)
    }

    @Test
    fun `placeholders needing a smaller size set the shared size`() {
        val result = fitSharedMnemonicFontSize(
            wordLists = listOf(listOf("cat"), listOf("secret")),
            wordBudgetPx = { 100 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(16.5.sp, fits = true), result)
    }

    @Test
    fun `actual word that never fits wraps both reveal states`() {
        val result = fitSharedMnemonicFontSize(
            wordLists = listOf(listOf("category"), listOf("secret")),
            wordBudgetPx = { 80 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(12.sp, fits = false), result)
    }

    @Test
    fun `placeholder that never fits wraps both reveal states`() {
        val result = fitSharedMnemonicFontSize(
            wordLists = listOf(listOf("cat"), listOf("secret")),
            wordBudgetPx = { 60 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(12.sp, fits = false), result)
    }

    @Test
    fun `no word lists keep the full font size`() {
        val result = fitSharedMnemonicFontSize(
            wordLists = emptyList(),
            wordBudgetPx = { 0 },
            measureWordPx = measureWord,
        )

        assertEquals(MnemonicFontFit(17.sp, fits = true), result)
    }

    @Test
    fun `word budget subtracts the column gap, label and label gap`() {
        assertEquals(78, mnemonicWordBudgetPx(gridWidthPx = 247, columnGapPx = 32, labelWidthPx = 21, labelGapPx = 8))
    }

    @Test
    fun `word budget is never negative`() {
        assertEquals(0, mnemonicWordBudgetPx(gridWidthPx = 40, columnGapPx = 32, labelWidthPx = 21, labelGapPx = 8))
    }
}
