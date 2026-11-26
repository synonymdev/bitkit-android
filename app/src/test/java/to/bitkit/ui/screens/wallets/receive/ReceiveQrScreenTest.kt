package to.bitkit.ui.screens.wallets.receive

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.models.NodeLifecycleState
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.WalletState
import to.bitkit.ui.theme.AppThemeSurface

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class ReceiveQrScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `without a way to receive over Lightning the spending and auto tabs are hidden`() {
        setContent(canReceiveWithSpark = false)

        composeRule.onNodeWithTag("Tab-savings").assertExists()
        composeRule.onNodeWithTag("Tab-spending").assertDoesNotExist()
        composeRule.onNodeWithTag("Tab-auto").assertDoesNotExist()
    }

    @Test
    fun `a usable spark wallet shows the spending and auto tabs`() {
        setContent(canReceiveWithSpark = true)

        composeRule.onNodeWithTag("Tab-spending").assertExists()
        composeRule.onNodeWithTag("Tab-auto").assertExists()
    }

    private fun setContent(canReceiveWithSpark: Boolean) {
        composeRule.setContent {
            AppThemeSurface {
                ReceiveQrScreen(
                    walletState = WalletState(),
                    lightningState = LightningState(nodeLifecycleState = NodeLifecycleState.Running),
                    onClickEditInvoice = {},
                    initialTab = ReceiveTab.SPENDING,
                    canReceiveWithSpark = canReceiveWithSpark,
                )
            }
        }
        composeRule.waitForIdle()
    }
}
