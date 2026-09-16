package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.BalanceState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

/**
 * Regression pins for #811 — the three funding options stay distinct and keep their own routes.
 *
 * "Use Other Wallet" deliberately opens the receive/CJIT flow (matching iOS `FundingOptions`);
 * manual external-node setup is the third option. Swapping those two is the regression.
 *
 * Note on selectors: `FundTransfer` tags two overlapping nodes — the button itself and a
 * conditional full-size overlay that only becomes clickable at a zero fundable balance. Selecting
 * by index would depend on semantics ordering, so these tests filter on "clickable and enabled",
 * which is what actually distinguishes the two at either balance.
 */
@HiltAndroidTest
@ComposeUi
class FundingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    private var transferClicks = 0
    private var fundClicks = 0
    private var manualClicks = 0

    @Before
    fun setup() {
        hiltRule.inject()
        transferClicks = 0
        fundClicks = 0
        manualClicks = 0
    }

    private fun setContent(fundableBalance: ULong = 100_000uL, isGeoBlocked: Boolean = false) {
        composeTestRule.setContent {
            CompositionLocalProvider(
                LocalBalances provides BalanceState(channelFundableBalance = fundableBalance)
            ) {
                AppThemeSurface {
                    FundingScreen(
                        isGeoBlocked = isGeoBlocked,
                        onTransfer = { transferClicks++ },
                        onFund = { fundClicks++ },
                        onManual = { manualClicks++ },
                    )
                }
            }
        }
    }

    // A disabled Compose button still reports an OnClick action, so `hasClickAction()` alone matches
    // both nodes at a zero balance — the enabled check is what makes the selection deterministic.
    private fun clickableTransferOption() = composeTestRule
        .onAllNodesWithTag("FundTransfer")
        .filterToOne(hasClickAction() and isEnabled())

    @Test
    fun whenScreenLoaded_shouldShowAllThreeFundingOptions() {
        setContent()

        clickableTransferOption().assertExists()
        composeTestRule.onNodeWithTag("FundReceive").assertExists()
        composeTestRule.onNodeWithTag("FundManual").assertExists()
    }

    @Test
    fun whenUseOtherWalletTapped_shouldInvokeOnlyOnFund() {
        setContent()

        composeTestRule.onNodeWithTag("FundReceive").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, fundClicks)
        assertEquals(0, manualClicks)
        assertEquals(0, transferClicks)
    }

    @Test
    fun whenManualSetupTapped_shouldInvokeOnlyOnManual() {
        setContent()

        composeTestRule.onNodeWithTag("FundManual").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, manualClicks)
        assertEquals(0, fundClicks)
        assertEquals(0, transferClicks)
    }

    @Test
    fun whenTransferFromSavingsTapped_shouldInvokeOnlyOnTransfer() {
        setContent()

        clickableTransferOption().performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, transferClicks)
        assertEquals(0, fundClicks)
        assertEquals(0, manualClicks)
    }

    @Test
    fun whenFundableBalanceIsZero_transferShouldNotRouteToTheTransferFlow() {
        setContent(fundableBalance = 0uL)

        // At a zero fundable balance the button is disabled and the overlay takes the gesture to
        // raise the no-funds alert, so the transfer callback must not fire.
        clickableTransferOption().performClick()
        composeTestRule.waitForIdle()

        assertEquals(0, transferClicks)
        assertEquals(0, fundClicks)
        assertEquals(0, manualClicks)
    }
}
