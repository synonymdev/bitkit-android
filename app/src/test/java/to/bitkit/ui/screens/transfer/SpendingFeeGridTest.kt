package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.repositories.SparkClaimSpeed
import to.bitkit.ui.screens.transfer.SparkFundingContract.Quote
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SpendingFeeGridTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val quote = Quote(
        address = "bcrt1qdeposit",
        amountSats = 50_000,
        networkFeeSats = 577,
        instantFeeSats = 1_093,
        laterFeeSats = 198,
        spendableSats = 158_234,
    )

    @Test
    fun `no fee tile carries an estimate prefix`() {
        setContent(SparkClaimSpeed.Instant)

        val prefixed = composeRule.onAllNodes(hasText("±", substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue(prefixed.isEmpty())
    }

    @Test
    fun `the service fee tile shows the claim fee of the selected speed`() {
        setContent(SparkClaimSpeed.Later)

        composeRule.onNode(
            hasText("SERVICE FEES") and hasAnyAncestor(hasTestTag("SpendingConfirmServiceFee")),
            useUnmergedTree = true,
        ).assertExists()
        composeRule.onNode(
            hasText("198", substring = true) and hasAnyAncestor(hasTestTag("SpendingConfirmServiceFee")),
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun `the address row shows only when an address is given`() {
        setContent(SparkClaimSpeed.Instant, address = quote.address)

        composeRule.onNodeWithTag("SpendingConfirmAddress", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("ADDRESS (CONFIRM ON DEVICE)").assertExists()
        composeRule.onNodeWithText(quote.address).assertExists()
    }

    @Test
    fun `the grid has no address row by default`() {
        setContent(SparkClaimSpeed.Instant)

        composeRule.onNodeWithTag("SpendingConfirmAddress", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a tap on the grid asks to switch the unit`() {
        var clicks = 0
        setContent(SparkClaimSpeed.Instant, onClick = { clicks++ })
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())

        composeRule.onNodeWithTag("SpendingConfirmFees").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun `the grid is not clickable without a click handler`() {
        setContent(SparkClaimSpeed.Instant)

        composeRule.onNodeWithTag(
            "SpendingConfirmFees"
        ).assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    private fun setContent(speed: SparkClaimSpeed, address: String? = null, onClick: (() -> Unit)? = null) {
        composeRule.setContent {
            // Inspection mode formats amounts without the currency view model, as previews do.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    SpendingFeeGrid(quote = quote, speed = speed, address = address, onClick = onClick)
                }
            }
        }
    }
}
