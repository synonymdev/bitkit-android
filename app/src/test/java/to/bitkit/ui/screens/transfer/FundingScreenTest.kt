package to.bitkit.ui.screens.transfer

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class FundingScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `hardware row opens the hardware transfer once spark is ready`() {
        var clicks = 0
        composeRule.setContent {
            AppThemeSurface { FundingScreen(canFundWithSpark = true, onHardware = { clicks++ }) }
        }
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())

        composeRule.onNodeWithTag("FundHardware").performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `hardware row stays disabled while spark is not ready`() {
        var clicks = 0
        composeRule.setContent {
            AppThemeSurface { FundingScreen(canFundWithSpark = false, onHardware = { clicks++ }) }
        }
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())

        composeRule.onNodeWithTag("FundHardware").performScrollTo().assertIsNotEnabled().performClick()

        assertEquals(0, clicks)
    }

    @Test
    fun `on-chain funding rows stay disabled when spark watches another chain`() {
        composeRule.setContent {
            AppThemeSurface { FundingScreen(canFundWithSpark = true, canFundOnChain = false) }
        }

        composeRule.onNodeWithTag("FundTransfer").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("FundHardware").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("FundReceive").performScrollTo().assertIsEnabled()
    }
}
