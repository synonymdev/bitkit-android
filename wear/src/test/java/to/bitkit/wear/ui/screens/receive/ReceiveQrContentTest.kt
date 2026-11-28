package to.bitkit.wear.ui.screens.receive

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.screens.home.DisplayAmount
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** What the receive QR screen shows once paid, in ambient mode and while it rests. */
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round")
@RunWith(RobolectricTestRunner::class)
class ReceiveQrContentTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val paid = ReceiveUiState(paidAmount = DisplayAmount(symbol = "₿", text = "50 000"))
    private val shown = ReceiveUiState(qrContent = "LNBCRT1TEST")
    private var shows = 0

    @Test
    fun `a paid invoice shows the check with its amount`() {
        setContent(paid, isAmbient = false)

        composeRule.onNodeWithTag("WatchReceivePaid").assertExists()
        composeRule.onNodeWithContentDescription("Paid").assertExists()
        composeRule.onNodeWithTag("WatchReceivePaidAmount", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("50 000", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a paid invoice in ambient mode shows the check without its amount`() {
        setContent(paid, isAmbient = true)

        composeRule.onNodeWithTag("WatchReceivePaid").assertExists()
        composeRule.onNodeWithContentDescription("Paid").assertExists()
        composeRule.onNodeWithTag("WatchReceivePaidAmount", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("50 000", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("₿", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("WatchReceiveHidden").assertDoesNotExist()
    }

    @Test
    fun `a tap anywhere on the resting screen shows the qr again`() {
        setContent(shown, isAmbient = false, isResting = true)
        val hidden = composeRule.onNodeWithTag("WatchReceiveHidden")

        hidden.assertContentDescriptionEquals("QR hidden")
        assertEquals(composeRule.onRoot().fetchSemanticsNode().size, hidden.fetchSemanticsNode().size)
        // The click debounce counts from the uptime, which starts at zero here.
        ShadowSystemClock.advanceBy(2.seconds.toJavaDuration())
        hidden.performTouchInput { click(percentOffset(0.5f, 0.1f)) }
        composeRule.waitForIdle()

        assertEquals(1, shows)
    }

    @Test
    fun `the qr hidden by ambient mode takes no tap`() {
        setContent(shown, isAmbient = true)

        composeRule.onNodeWithTag("WatchReceiveHidden").assertHasNoClickAction()
        composeRule.onNodeWithTag("WatchReceiveQr").assertDoesNotExist()
    }

    private fun setContent(uiState: ReceiveUiState, isAmbient: Boolean, isResting: Boolean = false) {
        composeRule.setContent {
            WearPreview {
                ReceiveQrContent(
                    uiState = uiState,
                    isAmbient = isAmbient,
                    onRefreshClick = {},
                    isResting = isResting,
                    onShowClick = { shows++ },
                )
            }
        }
    }
}
