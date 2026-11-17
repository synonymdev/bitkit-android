package to.bitkit.ui.screens.transfer.hardware

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SpendingHwSignedScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Config(sdk = [34], qualifiers = "w360dp-h640dp")
    @Test
    fun `the check sits 32 dp under the fee grid on a small screen`() {
        val quote = Quote(
            address = "bcrt1q",
            amountSats = 50_000,
            networkFeeSats = 1_141,
            instantFeeSats = 1_350,
            laterFeeSats = 198,
            spendableSats = 120_000,
        )
        composeRule.setContent {
            // Inspection mode formats amounts without the currency view model, as previews do.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface { Content(state = UiState(quote = quote, hwWalletId = "trezor")) }
            }
        }

        val grid = composeRule.onNodeWithTag("SpendingConfirmFees").getUnclippedBoundsInRoot()
        val check = composeRule.onNodeWithTag("HardwareTransferSignedCheck").getUnclippedBoundsInRoot()

        assertEquals(32f, (check.top - grid.bottom).value, 0.5f)
    }
}
