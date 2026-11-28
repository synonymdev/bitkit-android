package to.bitkit.wear.ui.screens.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import kotlin.test.assertEquals

/**
 * The effect that leaves Home sits above the pager, so it is composed here with no page at all: the Settings page
 * that asked for the removal may be disposed by the time the wallet is gone.
 */
@Config(sdk = [34], qualifiers = "w227dp-h227dp-round")
@RunWith(RobolectricTestRunner::class)
class WalletExitEffectTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var removals = 0
    private var keyLosses = 0
    private var wallet by mutableStateOf(WalletState(WalletStatus.Ready))

    private fun setContent() = composeRule.setContent {
        WalletExitEffect(
            isRemoved = wallet.status == WalletStatus.Removed,
            isMissing = wallet.status == WalletStatus.Missing,
            onSeedKeyLoss = { keyLosses++ },
            onWalletRemove = { removals++ },
        )
    }

    @Test
    fun `a wallet removed while no settings page is composed leaves home once`() {
        setContent()
        composeRule.runOnIdle { assertEquals(0, removals) }

        wallet = WalletState(WalletStatus.Missing)

        composeRule.runOnIdle {
            assertEquals(1, removals)
            assertEquals(0, keyLosses)
        }
    }

    @Test
    fun `a wallet deleted for a lost key leaves home for its notice, not for onboarding`() {
        setContent()

        wallet = WalletState(WalletStatus.Removed)

        composeRule.runOnIdle {
            assertEquals(1, keyLosses)
            assertEquals(0, removals)
        }
    }

    @Test
    fun `a wallet that is still there stays on home`() {
        setContent()

        wallet = WalletState(WalletStatus.Paused)

        composeRule.runOnIdle {
            assertEquals(0, removals)
            assertEquals(0, keyLosses)
        }
    }
}
