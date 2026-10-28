package to.bitkit.ui.screens.wallets.receive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class EditInvoiceAmountLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var amountInputViewModel: AmountInputViewModel
    private var continueCount = 0

    @Test
    fun compactEditorContinueIsVisibleAndCannotPressZeroKey() {
        setContent(fontScale = 1f)
        assertControlsAndContinueTouch()
    }

    @Test
    fun compactEditorWithLargerTextKeepsContinueBelowKeypad() {
        setContent(fontScale = 1.3f)
        assertControlsAndContinueTouch()
    }

    private fun setContent(fontScale: Float) {
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    amountInputViewModel = previewAmountInputViewModel(sats = 200_000)
                    Box(Modifier.requiredSize(360.dp, 520.dp).testTag("EditAmountViewport")) {
                        EditInvoiceContent(
                            amountInputViewModel = amountInputViewModel,
                            noteText = "Keep this note",
                            keyboardVisible = true,
                            tags = persistentListOf("test"),
                            onBack = {},
                            onContinueKeyboard = { continueCount++ },
                            onClickBalance = {},
                            onContinueGeneral = {},
                            onClickAddTag = {},
                            onTextChanged = {},
                            onClickTag = {},
                        )
                    }
                }
            }
        }
    }

    private fun assertControlsAndContinueTouch() {
        val viewport = composeTestRule.onNodeWithTag("EditAmountViewport").getUnclippedBoundsInRoot()
        for (tag in listOf("N1", "N0", "ReceiveNumberPadSubmit")) {
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        val keypad = composeTestRule.onNodeWithTag("ReceiveNumberField").getUnclippedBoundsInRoot()
        val submit = composeTestRule.onNodeWithTag("ReceiveNumberPadSubmit")
        assertTrue(keypad.bottom < submit.getUnclippedBoundsInRoot().top)
        submit.performTouchInput { click() }
        composeTestRule.runOnIdle {
            assertEquals(1, continueCount)
            assertEquals(200_000L, amountInputViewModel.uiState.value.sats)
        }
    }
}
