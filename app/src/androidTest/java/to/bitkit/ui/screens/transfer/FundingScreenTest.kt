package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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

    @Test
    fun whenScreenLoaded_shouldShowAllThreeFundingOptions() {
        setContent()

        composeTestRule.onAllNodesWithTag("FundTransfer")[0].assertExists()
        composeTestRule.onAllNodesWithTag("FundReceive")[0].assertExists()
        composeTestRule.onAllNodesWithTag("FundManual")[0].assertExists()
    }

    @Test
    fun whenUseOtherWalletTapped_shouldInvokeOnlyOnFund() {
        setContent()

        composeTestRule.onAllNodesWithTag("FundReceive")[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, fundClicks)
        assertEquals(0, manualClicks)
        assertEquals(0, transferClicks)
    }

    @Test
    fun whenManualSetupTapped_shouldInvokeOnlyOnManual() {
        setContent()

        composeTestRule.onAllNodesWithTag("FundManual")[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, manualClicks)
        assertEquals(0, fundClicks)
        assertEquals(0, transferClicks)
    }

    @Test
    fun whenTransferFromSavingsTapped_shouldInvokeOnlyOnTransfer() {
        setContent()

        composeTestRule.onAllNodesWithTag("FundTransfer")[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, transferClicks)
        assertEquals(0, fundClicks)
        assertEquals(0, manualClicks)
    }
}
