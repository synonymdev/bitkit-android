package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.ext.createChannelDetails
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals

@ComposeUi
class ReceiveAutoTabSelectionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var lightningState by mutableStateOf(STATE_WITHOUT_INBOUND)
    private val editedTabs = mutableListOf<ReceiveTab>()

    @Test
    fun keepsTabPickedByTapWhenAutoBecomesAvailable() {
        setContent()

        composeTestRule.onNodeWithTag("Tab-spending").performClick()
        composeTestRule.onNodeWithTag("Tab-savings").performClick()
        composeTestRule.waitForIdle()

        makeAutoAvailable()

        assertEquals(ReceiveTab.SAVINGS, selectedTab())
    }

    @Test
    fun jumpsToAutoWhenNoTabWasPicked() {
        setContent()
        composeTestRule.waitForIdle()

        makeAutoAvailable()

        assertEquals(ReceiveTab.AUTO, selectedTab())
    }

    private fun setContent() {
        composeTestRule.setContent {
            AppThemeSurface {
                ReceiveQrScreen(
                    cjitInvoice = null,
                    walletState = WALLET_STATE,
                    lightningState = lightningState,
                    onClickEditInvoice = { editedTabs += it },
                    onClickReceiveCjit = {},
                )
            }
        }
    }

    private fun makeAutoAvailable() {
        composeTestRule.runOnIdle { lightningState = STATE_WITH_INBOUND }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("Tab-auto").assertIsDisplayed()
    }

    private fun selectedTab(): ReceiveTab {
        composeTestRule.onAllNodesWithTag("SpecifyInvoiceButton")[0].performClick()
        composeTestRule.waitForIdle()
        return editedTabs.last()
    }

    private companion object {
        const val ADDRESS = "bcrt1qreceiveaddress"
        const val BOLT11 = "lnbcrt1invoice"
        val WALLET_STATE = WalletState(
            onchainAddress = ADDRESS,
            bolt11 = BOLT11,
            bip21 = "bitcoin:$ADDRESS?lightning=$BOLT11",
        )
        val STATE_WITHOUT_INBOUND = LightningState(nodeLifecycleState = NodeLifecycleState.Running)
        val STATE_WITH_INBOUND = LightningState(
            nodeLifecycleState = NodeLifecycleState.Running,
            channels = persistentListOf(
                createChannelDetails().copy(
                    isChannelReady = true,
                    isUsable = true,
                    inboundCapacityMsat = 100_000_000u,
                )
            ),
        )
    }
}
