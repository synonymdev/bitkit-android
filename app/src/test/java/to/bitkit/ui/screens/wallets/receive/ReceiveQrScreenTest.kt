package to.bitkit.ui.screens.wallets.receive

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletState
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ReceiveQrScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the spending onboarding shows while the node starts with its button disabled`() {
        setContent(NodeLifecycleState.Starting)

        composeRule.onNodeWithText("Enjoy instant and cheap transactions", substring = true).assertExists()
        composeRule.onNodeWithText("Receive Lightning Funds").assertExists()
        composeRule.onNodeWithTag("ShowDetails").assertIsNotEnabled()
    }

    @Test
    fun `the spending onboarding button is enabled once the node runs`() {
        setContent(NodeLifecycleState.Running)

        composeRule.onNodeWithTag("ShowDetails").assertIsEnabled()
    }

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `the spending onboarding card takes figma's share of the screen height and gap`() {
        setContent(NodeLifecycleState.Running)

        val card = composeRule.onNodeWithTag("ReceiveSpendingCard").getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag("ShowDetails").getUnclippedBoundsInRoot()

        assertEquals(997f * 343f / 812f, card.height.value, 0.5f)
        assertEquals(448f - 2 * 16f, card.width.value, 0.5f)
        assertEquals(997f * 122f / 812f, (button.top - card.bottom).value, 0.5f)
    }

    /** Opens the Spending tab, as the screen starts on Savings while Lightning can't receive. */
    private fun setContent(nodeState: NodeLifecycleState) {
        composeRule.setContent {
            AppThemeSurface {
                ReceiveQrScreen(
                    walletState = WalletState(),
                    lightningState = LightningState(nodeLifecycleState = nodeState),
                    onClickReceiveCjit = {},
                    onClickEditInvoice = {},
                    initialTab = ReceiveTab.SPENDING,
                )
            }
        }
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())
        composeRule.onNodeWithTag("Tab-spending").performClick()
        composeRule.waitForIdle()
    }
}
