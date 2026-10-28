package to.bitkit.ui.screens.wallets.send

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
import to.bitkit.models.BalanceState
import to.bitkit.models.NodeLifecycleState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ComposeUi
class SendAmountLayoutTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var amountInputViewModel: AmountInputViewModel
    private var continueCount = 0

    @Test
    fun compactOnchainContinueIsVisibleAndCannotPressZeroKey() {
        setContent(SendMethod.ONCHAIN)

        assertKeypadAndContinueFit()
        tapContinueAndAssertAmountUnchanged()
    }

    @Test
    fun compactLightningBalanceScrollKeepsKeypadAndContinueTouchable() {
        setContent(SendMethod.LIGHTNING, fontScale = 1.3f)

        assertKeypadAndContinueFit()
        val keypadBounds = composeTestRule.onNodeWithTag("SendAmountNumberPad").getUnclippedBoundsInRoot()
        val continueBounds = composeTestRule.onNodeWithTag("ContinueAmount").getUnclippedBoundsInRoot()
        composeTestRule.onNodeWithTag("AvailableAmount").performScrollTo().assertIsDisplayed()
        assertEquals(keypadBounds, composeTestRule.onNodeWithTag("SendAmountNumberPad").getUnclippedBoundsInRoot())
        assertEquals(continueBounds, composeTestRule.onNodeWithTag("ContinueAmount").getUnclippedBoundsInRoot())
        tapContinueAndAssertAmountUnchanged()
    }

    private fun setContent(method: SendMethod, fontScale: Float = 1f) {
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                    amountInputViewModel = previewAmountInputViewModel(sats = 200_000)
                    Box(Modifier.requiredSize(360.dp, 520.dp).testTag("SendAmountViewport")) {
                        SendAmountContent(
                            nodeLifecycleState = NodeLifecycleState.Running,
                            uiState = SendUiState(payMethod = method, amount = 200_000u, isAmountInputValid = true),
                            amountInputViewModel = amountInputViewModel,
                            balances = BalanceState(maxSendOnchainSats = 3_000_000u, maxSendLightningSats = 3_000_000u),
                            onContinue = { continueCount++ },
                        )
                    }
                }
            }
        }
    }

    private fun assertKeypadAndContinueFit() {
        val viewport = composeTestRule.onNodeWithTag("SendAmountViewport").getUnclippedBoundsInRoot()
        for (tag in listOf("N1", "N0", "ContinueAmount")) {
            val node = composeTestRule.onNodeWithTag(tag)
            node.assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            assertTrue(bounds.left >= viewport.left && bounds.right <= viewport.right)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
        val keypad = composeTestRule.onNodeWithTag("SendAmountNumberPad").getUnclippedBoundsInRoot()
        val continueButton = composeTestRule.onNodeWithTag("ContinueAmount").getUnclippedBoundsInRoot()
        assertTrue(keypad.bottom < continueButton.top)
    }

    private fun tapContinueAndAssertAmountUnchanged() {
        composeTestRule.onNodeWithTag("ContinueAmount").performTouchInput { click() }
        composeTestRule.runOnIdle {
            assertEquals(1, continueCount)
            assertEquals(200_000L, amountInputViewModel.uiState.value.sats)
        }
    }
}
