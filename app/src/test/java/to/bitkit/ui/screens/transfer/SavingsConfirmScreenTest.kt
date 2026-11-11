package to.bitkit.ui.screens.transfer

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.SavingsSwapQuote
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

    @Config(sdk = [34], qualifiers = "w360dp-h640dp")
    @Test
    fun `the piggybank keeps its size when the fees slider and spark row make the content tall`() {
        composeRule.setContent {
            AppThemeSurface {
                SavingsConfirmContent(
                    fallbackAmount = 69_000u,
                    sparkAmount = 12_000u,
                    sparkFeeSats = 1_470u,
                    isSparkFeePricing = false,
                    isSparkUnpriced = false,
                    isSparkTooSmall = false,
                    isSwapEnabled = true,
                    quote = SavingsSwapQuote(
                        amountSat = 69_000u,
                        networkFeeSat = 520u,
                        swapFeeSat = 245u,
                        receiveSat = 68_235u,
                    ),
                    isQuoteLoading = false,
                    minSat = 25_000u,
                    maxSat = 69_000u,
                    onAmountChange = {},
                    hasMultiple = true,
                    hasSelected = false,
                )
            }
        }

        val piggy = composeRule.onNodeWithTag("SavingsConfirmPiggybank").getUnclippedBoundsInRoot()

        assertEquals(360f * 256f / 402f, piggy.width.value, 0.5f)
        assertEquals(piggy.width.value, piggy.height.value, 0.5f)
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
