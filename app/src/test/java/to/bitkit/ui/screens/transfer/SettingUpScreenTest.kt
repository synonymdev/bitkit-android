package to.bitkit.ui.screens.transfer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.repositories.SparkClaimOffer
import to.bitkit.repositories.SparkDepositClaim
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Config(sdk = [34], qualifiers = "w320dp-h568dp")
@RunWith(RobolectricTestRunner::class)
class SettingUpScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `fee changed steps and their label sit above the side by side buttons on a small screen`() {
        setContent(repriced)

        val label = composeRule.onNodeWithText("Fast Transfer Fee Changed").getUnclippedBoundsInRoot()
        val wait = composeRule.onNodeWithTag("SettingUpWait").getUnclippedBoundsInRoot()
        val approve = composeRule.onNodeWithTag("SettingUpPay").getUnclippedBoundsInRoot()

        assertTrue(label.bottom <= wait.top)
        assertTrue(wait.top == approve.top && wait.right < approve.left)
        composeRule.onNodeWithText("FEE CHANGED", substring = true).assertExists()
        composeRule.onNodeWithText("Approve").assertExists()
        composeRule.onNodeWithText("The fast transfer fee is now ₿ ", substring = true).assertExists()
        composeRule.onNodeWithText("Approve to access your funds quickly, or wait ±30 min.", substring = true)
            .assertExists()
        composeRule.onNodeWithTag("SettingUpCoins").assertExists()
    }

    @Test
    fun `normal speed steps and their label sit above the button on a small screen`() {
        setContent(SparkDepositClaim.Later)

        val label = composeRule.onNodeWithText("Processing Payment").getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag("TransferSuccess-button").getUnclippedBoundsInRoot()

        assertTrue(label.bottom <= button.top)
        composeRule.onNodeWithText("This should take about 30 minutes.", substring = true).assertExists()
    }

    @Test
    fun `no fast transfer shows the coins and the normal speed`() {
        setContent(SparkDepositClaim.Unavailable)

        composeRule.onNodeWithText("UNAVAILABLE", substring = true).assertExists()
        composeRule.onNodeWithText("Using Normal Transfer Speed").assertExists()
        composeRule.onNodeWithText("This should take about 30 minutes.", substring = true).assertExists()
        composeRule.onNodeWithTag("SettingUpCoins").assertExists()
    }

    @Test
    fun `adding names a few minutes`() {
        setContent(SparkDepositClaim.Adding)

        composeRule.onNodeWithText("Adding To Spending Balance").assertExists()
        composeRule.onNodeWithText("This should take a few minutes.", substring = true).assertExists()
    }

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `the rings fill the width less 16 per side and keep their size when repriced`() {
        setContent(SparkDepositClaim.Looking)
        val looking = composeRule.onNodeWithTag("TransferAnimation").getUnclippedBoundsInRoot()

        setContent(repriced)
        val repricedRings = composeRule.onNodeWithTag("TransferAnimation").getUnclippedBoundsInRoot()

        assertEquals(448f - 4 * 16f, looking.width.value, 0.5f)
        assertEquals(looking.width.value, repricedRings.width.value, 0.5f)
    }

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `the funds moved check takes the share of the screen width ios gives it`() {
        setContent(SparkDepositClaim.Claimed)

        val check = composeRule.onNodeWithTag("TransferSuccessCheck").getUnclippedBoundsInRoot()

        assertEquals(448f * 256f / 402f, check.width.value, 0.5f)
        assertEquals(check.width.value, check.height.value, 0.5f)
    }

    private var claimState by mutableStateOf<SparkDepositClaim?>(null)

    private fun setContent(claim: SparkDepositClaim) {
        val isFirst = claimState == null
        claimState = claim
        if (!isFirst) return composeRule.waitForIdle()
        composeRule.setContent {
            AppThemeSurface { Content(claim = requireNotNull(claimState)) }
        }
    }

    private val repriced = SparkDepositClaim.Repriced(
        SparkClaimOffer.Choice(
            txid = "txid",
            vout = 0u,
            earlyFeeSats = 1_850uL,
            earlyConfirmations = 0u,
            standardFeeSats = 198uL,
            standardConfirmations = 3u,
        )
    )
}
