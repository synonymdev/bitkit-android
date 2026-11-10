package to.bitkit.ui.screens.transfer

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
class SavingsConfirmScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the spark fee row shows while the fee is being priced`() {
        setContent(isSparkFeePricing = true, isSparkUnpriced = true)

        composeRule.onNodeWithText("SPARK FEE", substring = true, ignoreCase = true).assertExists()
        composeRule.onNodeWithTag("SavingsSparkFeeRetry").assertDoesNotExist()
    }

    @Test
    fun `an unpriced spark fee shows the quote error with a retry that prices it again`() {
        var retries = 0
        setContent(isSparkUnpriced = true, onSparkFeeRetry = { retries++ })
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())

        composeRule.onNodeWithText("Bitkit couldn't get the fees", substring = true).assertExists()
        composeRule.onNodeWithTag("SavingsSparkFeeRetry").performScrollTo().performClick()

        assertEquals(1, retries)
    }

    @Test
    fun `a spark amount too low to withdraw says so without a retry`() {
        setContent(isSparkUnpriced = true, isSparkTooSmall = true)

        composeRule.onNodeWithText("Amount is too low to transfer to savings.").assertExists()
        composeRule.onNodeWithTag("SavingsSparkFeeRetry").assertDoesNotExist()
    }

    private fun setContent(
        isSparkFeePricing: Boolean = false,
        isSparkUnpriced: Boolean = false,
        isSparkTooSmall: Boolean = false,
        onSparkFeeRetry: () -> Unit = {},
    ) {
        composeRule.setContent {
            AppThemeSurface {
                SavingsConfirmContent(
                    fallbackAmount = 0u,
                    sparkAmount = 12_000u,
                    sparkFeeSats = null,
                    isSparkFeePricing = isSparkFeePricing,
                    isSparkUnpriced = isSparkUnpriced,
                    isSparkTooSmall = isSparkTooSmall,
                    isSwapEnabled = false,
                    quote = null,
                    isQuoteLoading = false,
                    minSat = 0u,
                    maxSat = 0u,
                    onAmountChange = {},
                    hasMultiple = false,
                    hasSelected = false,
                    onSparkFeeRetry = onSparkFeeRetry,
                )
            }
        }
    }
}
