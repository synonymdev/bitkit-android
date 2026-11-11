package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SpendingFeeGridTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `instant marks only the claim fee tiles as estimates`() {
        setContent(SparkClaimSpeed.Instant)

        assertEstimates()
    }

    @Test
    fun `later marks only the claim fee tiles as estimates`() {
        setContent(SparkClaimSpeed.Later)

        assertEstimates()
    }

    private fun assertEstimates() {
        assertTrue(hasEstimate("SpendingConfirmSpendingFee"))
        assertTrue(hasEstimate("SpendingConfirmAmount"))
        assertFalse(hasEstimate("SpendingConfirmNetworkFee"))
        assertFalse(hasEstimate("SpendingConfirmTotal"))
    }

    private fun hasEstimate(tag: String): Boolean = composeRule
        .onAllNodes(hasText("±", substring = true) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .isNotEmpty()

    private fun setContent(speed: SparkClaimSpeed) {
        val quote = Quote(
            address = "bcrt1q",
            amountSats = 50_000,
            networkFeeSats = 577,
            instantFeeSats = 1_093,
            laterFeeSats = 198,
            spendableSats = 158_234,
        )
        composeRule.setContent {
            // Inspection mode formats amounts without the currency view model, as previews do.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface { SpendingFeeGrid(quote = quote, speed = speed) }
            }
        }
    }
}
