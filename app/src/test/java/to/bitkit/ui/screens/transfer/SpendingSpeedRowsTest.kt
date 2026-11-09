package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@RunWith(RobolectricTestRunner::class)
class SpendingSpeedRowsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the later fee fits inside its row on a narrow screen`() {
        val quote = Quote(
            address = "bcrt1q",
            amountSats = 50_000,
            networkFeeSats = 577,
            instantFeeSats = 1_350,
            laterFeeSats = 99,
            spendableSats = 158_234,
        )
        composeRule.setContent {
            // Inspection mode formats amounts without the currency view model, as previews do.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface { SpendingSpeedRows(quote = quote, speed = SparkClaimSpeed.Later, onSpeedChange = {}) }
            }
        }

        val row = composeRule.onNodeWithTag("SpendingConfirmLater").getUnclippedBoundsInRoot()
        val laterFee = composeRule.onNodeWithText("99", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val instantFee = composeRule.onNodeWithText("350", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(laterFee.right <= row.right, "fee ends at ${laterFee.right}, row at ${row.right}")
        assertEquals(instantFee.height.value, laterFee.height.value, 0.5f, "the later fee wraps to a second line")
    }
}
