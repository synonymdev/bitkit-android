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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class ReceiveAmountLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var amountInputViewModel: AmountInputViewModel
    private var continueCount = 0

    @Test
    fun compactSheetContinueIsVisibleAndCannotPressZeroKey() {
        setContent()

        assertKeypadAndContinueFit()
        composeTestRule.onNodeWithTag("ContinueAmount").performTouchInput { click() }

        composeTestRule.runOnIdle {
            assertEquals(1, continueCount)
            assertEquals(200_000L, amountInputViewModel.uiState.value.sats)
        }
    }

    @Test
    fun longErrorCanScrollWithoutMovingKeypadOrContinue() {
        setContent(
            error = "The primary node could not quote this receive request. " +
                "Check your connection and try again before approving a receive fee.",
            fontScale = 1.3f,
        )

        assertKeypadAndContinueFit()
        val keypadBounds = composeTestRule.onNodeWithTag("ReceiveNumberPad").getUnclippedBoundsInRoot()
        val continueBounds = composeTestRule.onNodeWithTag("ContinueAmount").getUnclippedBoundsInRoot()
        composeTestRule.onNodeWithTag("PrimaryReceiveError").performScrollTo().assertIsDisplayed()
        assertEquals(keypadBounds, composeTestRule.onNodeWithTag("ReceiveNumberPad").getUnclippedBoundsInRoot())
        assertEquals(continueBounds, composeTestRule.onNodeWithTag("ContinueAmount").getUnclippedBoundsInRoot())
        composeTestRule.onNodeWithTag("ContinueAmount").performTouchInput { click() }

        composeTestRule.runOnIdle {
            assertEquals(1, continueCount)
            assertEquals(200_000L, amountInputViewModel.uiState.value.sats)
        }
    }

    private fun setContent(error: String? = null, fontScale: Float = 1f) {
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    amountInputViewModel = previewAmountInputViewModel(sats = 200_000)
                    Box(Modifier.requiredSize(360.dp, 520.dp).testTag("ReceiveAmountViewport")) {
                        ReceiveAmountContent(
                            amountInputViewModel = amountInputViewModel,
                            isCreatingInvoice = false,
                            canContinue = true,
                            error = error,
                            onContinue = { continueCount++ },
                        )
                    }
                }
            }
        }
    }

    private fun assertKeypadAndContinueFit() {
        val viewport = composeTestRule.onNodeWithTag("ReceiveAmountViewport").getUnclippedBoundsInRoot()
        for (tag in listOf("N1", "N0", "ContinueAmount")) {
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        val keypad = composeTestRule.onNodeWithTag("ReceiveNumberPad").getUnclippedBoundsInRoot()
        val continueButton = composeTestRule.onNodeWithTag("ContinueAmount").getUnclippedBoundsInRoot()
        assertTrue(keypad.bottom < continueButton.top)
    }
}
