package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class CloseConnectionScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var showConfirmation by mutableStateOf(false)
    private var forceCloseCount = 0

    @Test
    fun forceCloseRequiresConfirmationAndCanBeCancelled() {
        setContent()

        composeTestRule.onNodeWithTag("ForceCloseConnection").performClick()
        composeTestRule.onNodeWithTag("ForceCloseConfirm").assertIsDisplayed()
        assertEquals(0, forceCloseCount)
        composeTestRule.onNodeWithTag("ForceCloseCancel").performClick()
        composeTestRule.onNodeWithTag("ForceCloseConfirm").assertDoesNotExist()
        assertEquals(0, forceCloseCount)
    }

    @Test
    fun forceCloseRunsOnlyAfterExplicitConfirmation() {
        setContent()

        composeTestRule.onNodeWithTag("ForceCloseConnection").performClick()
        composeTestRule.onNodeWithTag("ForceCloseConfirm").assertIsDisplayed()
        assertEquals(0, forceCloseCount)
        composeTestRule.onNodeWithTag("ForceCloseConfirm").performClick()
        assertEquals(1, forceCloseCount)
    }

    private fun setContent() {
        composeTestRule.setContent {
            AppThemeSurface {
                CloseConnectionContent(
                    showForceCloseConfirmation = showConfirmation,
                    onClickForceClose = { showConfirmation = true },
                    onCancelForceClose = { showConfirmation = false },
                    onConfirmForceClose = { forceCloseCount++ },
                )
            }
        }
    }
}
