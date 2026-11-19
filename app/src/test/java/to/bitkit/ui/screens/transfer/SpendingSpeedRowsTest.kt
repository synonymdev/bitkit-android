package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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

    private val quote = Quote(
        address = "bcrt1q",
        amountSats = 50_000,
        networkFeeSats = 577,
        instantFeeSats = 1_350,
        laterFeeSats = 99,
        spendableSats = 158_234,
    )

    @Test
    fun `the normal fee fits inside its row on a narrow screen`() {
        // Inspection mode formats amounts without the currency view model, as previews do.
        setRows(SparkClaimSpeed.Later)

        val row = composeRule.onNodeWithTag("SpendingConfirmLater").getUnclippedBoundsInRoot()
        val normalFee = composeRule.onNodeWithText("99", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val fastFee = composeRule.onNodeWithText("350", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

        assertTrue(normalFee.right <= row.right, "fee ends at ${normalFee.right}, row at ${row.right}")
        assertEquals(fastFee.height.value, normalFee.height.value, 0.5f, "the normal fee wraps to a second line")
    }

    @Test
    fun `fast caps its fee and normal estimates it`() {
        setRows(SparkClaimSpeed.Instant)

        rowText("SpendingConfirmInstant", "Fast").assertExists()
        rowText("SpendingConfirmInstant", "max").assertExists()
        rowText("SpendingConfirmLater", "Normal").assertExists()
        rowText("SpendingConfirmLater", "Ready in about 30 minutes").assertExists()
        rowText("SpendingConfirmLater", "est").assertExists()
    }

    @Test
    fun `without a quote the rows show no fee`() {
        setRows(SparkClaimSpeed.Instant, quote = null)

        rowText("SpendingConfirmInstant", "max").assertDoesNotExist()
        rowText("SpendingConfirmLater", "est").assertDoesNotExist()
    }

    private fun rowText(rowTag: String, text: String) =
        composeRule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true)

    @Test
    fun `only the chosen speed is selected`() {
        setRows(SparkClaimSpeed.Later)

        composeRule.onNodeWithTag("SpendingConfirmLater").assertIsSelected()
        composeRule.onNodeWithTag("SpendingConfirmInstant").assertIsNotSelected()
    }

    private fun setRows(speed: SparkClaimSpeed, quote: Quote? = this.quote) {
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface { SpendingSpeedRows(quote = quote, speed = speed, onSpeedChange = {}) }
            }
        }
    }
}
