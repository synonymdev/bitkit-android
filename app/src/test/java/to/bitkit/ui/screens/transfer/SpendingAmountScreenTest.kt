package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.models.PrimaryDisplay
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.CurrencyState
import to.bitkit.ui.screens.transfer.SparkFundingContract.UiState
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import to.bitkit.viewmodels.previewAmountInputViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SpendingAmountScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `number pad rows are 78 dp when the screen has room`() {
        setContent()

        val row = composeRule.onNodeWithTag("N1").getUnclippedBoundsInRoot()

        assertEquals(78f, row.height.value, 0.5f)
    }

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `the available row sits 32 dp under the amount and the number pad stays at the bottom`() {
        setContent()

        val amount = composeRule.onNodeWithTag("SpendingAmountNumberField").getUnclippedBoundsInRoot()
        val available = composeRule.onNodeWithTag("SendAmountNumberPad").getUnclippedBoundsInRoot()
        val lastRow = composeRule.onNodeWithTag("N0").getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag("SpendingAmountContinue").getUnclippedBoundsInRoot()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()

        assertEquals(32f, (available.top - amount.bottom).value, 0.5f)
        assertEquals(lastRow.bottom.value, button.top.value, 0.5f)
        assertEquals(root.bottom.value - 16f, button.bottom.value, 0.5f)
    }

    @Config(sdk = [34], qualifiers = "w360dp-h780dp")
    @Test
    fun `the available row keeps its 32 dp gap on a mid-size screen`() {
        setContent(statusBar = STATUS_BAR, navigationBar = NAVIGATION_BAR)

        val amount = composeRule.onNodeWithTag("SpendingAmountNumberField").getUnclippedBoundsInRoot()
        val available = composeRule.onNodeWithTag("SendAmountNumberPad").getUnclippedBoundsInRoot()
        val row = composeRule.onNodeWithTag("N1").getUnclippedBoundsInRoot()
        val lastRow = composeRule.onNodeWithTag("N0").getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag("SpendingAmountContinue").getUnclippedBoundsInRoot()

        assertEquals(32f, (available.top - amount.bottom).value, 0.5f)
        assertTrue(row.height.value >= 40f, "row is ${row.height}")
        assertTrue(lastRow.bottom <= button.top, "last row ends at ${lastRow.bottom}, continue at ${button.top}")
    }

    @Config(sdk = [34], qualifiers = "w360dp-h640dp")
    @Test
    fun `the amount screen fits a small screen with the continue button above the system bars`() {
        setContent(statusBar = STATUS_BAR, navigationBar = NAVIGATION_BAR)

        val row = composeRule.onNodeWithTag("N1").getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag("SpendingAmountContinue").getUnclippedBoundsInRoot()
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()

        val lastRow = composeRule.onNodeWithTag("N0").getUnclippedBoundsInRoot()
        assertTrue(row.height.value in 40f..50f, "row is ${row.height}")
        assertTrue(lastRow.bottom <= button.top, "last row ends at ${lastRow.bottom}, continue at ${button.top}")
        assertTrue(
            button.bottom <= root.bottom - NAVIGATION_BAR,
            "continue ends at ${button.bottom}, navigation bar starts at ${root.bottom - NAVIGATION_BAR}",
        )
    }

    @Config(sdk = [34])
    @Test
    fun `the amount follows a unit switched on another screen`() {
        val btc = CurrencyState(primaryDisplay = PrimaryDisplay.BITCOIN)
        val fiat = CurrencyState(primaryDisplay = PrimaryDisplay.FIAT)
        val viewModel = AmountInputViewModel(AmountInputHandler.stub()).apply { setSats(4_567, btc) }
        val reference = AmountInputViewModel(AmountInputHandler.stub()).apply { setSats(4_567, fiat) }
        var currencies by mutableStateOf(btc)
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    Content(
                        uiState = UiState(isNodeRunning = true, isMaxLoading = false, maxSats = 158_234),
                        amountInputViewModel = viewModel,
                        currencies = currencies,
                        onBackClick = {},
                        onClickQuarter = {},
                        onClickMaxAmount = {},
                        onConfirmAmount = {},
                    )
                }
            }
        }

        currencies = fiat
        composeRule.waitForIdle()
        viewModel.handleNumberPadInput("1", fiat)
        reference.handleNumberPadInput("1", fiat)

        assertEquals(reference.uiState.value.text, viewModel.uiState.value.text)
        assertEquals(reference.uiState.value.sats, viewModel.uiState.value.sats)
    }

    /** Pads the screen as the system bars do on a device, since Robolectric reports no insets. */
    private fun setContent(statusBar: Dp = 0.dp, navigationBar: Dp = 0.dp) {
        composeRule.setContent {
            // Inspection mode formats amounts without the currency view model, as previews do.
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    Box(Modifier.padding(top = statusBar, bottom = navigationBar)) {
                        Content(
                            uiState = UiState(isNodeRunning = true, isMaxLoading = false, maxSats = 158_234),
                            amountInputViewModel = previewAmountInputViewModel(currencies = CurrencyState()),
                            currencies = CurrencyState(),
                            onBackClick = {},
                            onClickQuarter = {},
                            onClickMaxAmount = {},
                            onConfirmAmount = {},
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val STATUS_BAR = 24.dp
        val NAVIGATION_BAR = 48.dp
    }
}
