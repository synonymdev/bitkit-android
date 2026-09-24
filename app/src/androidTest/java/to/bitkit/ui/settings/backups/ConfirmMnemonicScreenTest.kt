package to.bitkit.ui.settings.backups

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildAt
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@ComposeUi
class ConfirmMnemonicScreenTest {

    companion object {
        private val WORDS = listOf(
            "able", "baby", "cable", "dance", "eagle", "fabric",
            "gadget", "habit", "ice", "jacket", "kangaroo", "label",
        )

        /** Longer than the 500 ms click debounce, so the same node can be tapped again. */
        private const val CLICK_DEBOUNCE_WAIT_MS = 600L
    }

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setScreen(tester: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            AppThemeSurface {
                ConfirmMnemonicScreen(
                    uiState = BackupContract.UiState(bip39Mnemonic = WORDS.joinToString(" ")),
                    onContinue = {},
                    onBack = {},
                )
            }
        }
        if (tester != null) tester.setContent(content) else composeTestRule.setContent(content)
    }

    private fun tap(tag: String) {
        composeTestRule.onNodeWithTag(tag).performScrollTo().performClick()
        composeTestRule.waitForIdle()
    }

    private fun tapChip(word: String) = tap("Word-$word")

    private fun selectedWord(number: Int) = composeTestRule.onNodeWithTag("SelectedWord-$number")

    private fun assertSelectedWord(number: Int, word: String) {
        selectedWord(number).onChildAt(1).assertTextEquals(word)
    }

    private fun waitForClickDebounce() = SystemClock.sleep(CLICK_DEBOUNCE_WAIT_MS)

    private fun chipOrder(): List<String> = WORDS.sortedWith(
        compareBy(
            { composeTestRule.onNodeWithTag("Word-$it").fetchSemanticsNode().boundsInRoot.top },
            { composeTestRule.onNodeWithTag("Word-$it").fetchSemanticsNode().boundsInRoot.left },
        )
    )

    private fun completeFrom(position: Int) {
        WORDS.drop(position).forEach { tapChip(it) }
    }

    @Test
    fun tappingRedWord_clearsItAndReleasesItsChip() {
        setScreen()

        tapChip(WORDS[2])
        assertSelectedWord(1, WORDS[2])
        selectedWord(1).assertHasClickAction()

        tap("SelectedWord-1")
        assertSelectedWord(1, "")
        selectedWord(1).assertHasNoClickAction()

        waitForClickDebounce()
        completeFrom(0)
        composeTestRule.onNodeWithTag("ContinueConfirmMnemonic").assertIsEnabled()
    }

    @Test
    fun correctWord_isNotClickableAndStaysSelected() {
        setScreen()

        tapChip(WORDS[0])
        assertSelectedWord(1, WORDS[0])
        selectedWord(1).assertHasNoClickAction()

        waitForClickDebounce()
        tapChip(WORDS[0])
        assertSelectedWord(1, WORDS[0])
        composeTestRule.onNodeWithTag("ContinueConfirmMnemonic").assertIsNotEnabled()
    }

    @Test
    fun anotherChipAfterWrongWord_doesNothing_andWrongChipClearsIt() {
        setScreen()

        tapChip(WORDS[2])
        tapChip(WORDS[0])
        assertSelectedWord(1, WORDS[2])
        assertSelectedWord(2, "")

        waitForClickDebounce()
        tapChip(WORDS[2])
        assertSelectedWord(1, "")
        selectedWord(1).assertHasNoClickAction()
    }

    @Test
    fun chipOrderAndSelection_surviveRecreation() {
        val tester = StateRestorationTester(composeTestRule)
        setScreen(tester)

        tapChip(WORDS[0])
        tapChip(WORDS[2])
        val orderBefore = chipOrder()
        assertNotEquals(WORDS, orderBefore, "chips should be shuffled")

        tester.emulateSavedInstanceStateRestore()
        composeTestRule.waitForIdle()

        assertEquals(orderBefore, chipOrder())
        assertSelectedWord(1, WORDS[0])
        assertSelectedWord(2, WORDS[2])
        selectedWord(2).assertHasClickAction()

        tapChip(WORDS[2])
        assertSelectedWord(2, "")

        waitForClickDebounce()
        completeFrom(1)
        composeTestRule.onNodeWithTag("ContinueConfirmMnemonic").assertIsEnabled()
    }
}
