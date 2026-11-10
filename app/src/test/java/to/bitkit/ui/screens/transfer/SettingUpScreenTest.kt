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
import to.bitkit.repositories.SparkClaimSpeed
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
    fun `repriced steps and their label sit above the side by side buttons on a small screen`() {
        setContent(repriced, SparkClaimSpeed.Instant)

        val label = composeRule.onNodeWithText("Adding To Spending").getUnclippedBoundsInRoot()
        val wait = composeRule.onNodeWithTag("SettingUpWait").getUnclippedBoundsInRoot()
        val pay = composeRule.onNodeWithTag("SettingUpPay").getUnclippedBoundsInRoot()

        assertTrue(label.bottom <= wait.top)
        assertTrue(wait.top == pay.top && wait.right < pay.left)
        composeRule.onNodeWithText("SPEED: INSTANT").assertExists()
    }

    @Test
    fun `later steps and their label sit above the button on a small screen`() {
        setContent(SparkDepositClaim.Later, SparkClaimSpeed.Instant)

        val label = composeRule.onNodeWithText("Payment Successful").getUnclippedBoundsInRoot()
        val button = composeRule.onNodeWithTag("TransferSuccess-button").getUnclippedBoundsInRoot()

        assertTrue(label.bottom <= button.top)
        composeRule.onNodeWithText("SPEED: LATER").assertExists()
    }

    @Test
    fun `a transfer added after 3 confirmations shows the later speed`() {
        setContent(SparkDepositClaim.Unavailable, SparkClaimSpeed.Instant)

        composeRule.onNodeWithText("SPEED: LATER").assertExists()
    }

    @Test
    fun `a transfer spark has not seen yet shows the later speed`() {
        setContent(SparkDepositClaim.NotSeen, SparkClaimSpeed.Instant)

        composeRule.onNodeWithText("SPEED: LATER").assertExists()
    }

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `the rings fill the width less 16 per side and keep their size when repriced`() {
        setContent(SparkDepositClaim.Looking, SparkClaimSpeed.Instant)
        val looking = composeRule.onNodeWithTag("TransferAnimation").getUnclippedBoundsInRoot()

        setContent(repriced, SparkClaimSpeed.Instant)
        val repricedRings = composeRule.onNodeWithTag("TransferAnimation").getUnclippedBoundsInRoot()

        assertEquals(448f - 4 * 16f, looking.width.value, 0.5f)
        assertEquals(looking.width.value, repricedRings.width.value, 0.5f)
    }

    @Config(sdk = [34], qualifiers = "w448dp-h997dp")
    @Test
    fun `the funds moved check takes the share of the screen width ios gives it`() {
        setContent(SparkDepositClaim.Claimed, SparkClaimSpeed.Instant)

        val check = composeRule.onNodeWithTag("TransferSuccessCheck").getUnclippedBoundsInRoot()

        assertEquals(448f * 256f / 402f, check.width.value, 0.5f)
        assertEquals(check.width.value, check.height.value, 0.5f)
    }

    private var claimState by mutableStateOf<Pair<SparkDepositClaim, SparkClaimSpeed>?>(null)

    private fun setContent(claim: SparkDepositClaim, speed: SparkClaimSpeed) {
        val isFirst = claimState == null
        claimState = claim to speed
        if (!isFirst) return composeRule.waitForIdle()
        composeRule.setContent {
            val (shownClaim, shownSpeed) = requireNotNull(claimState)
            AppThemeSurface { Content(claim = shownClaim, speed = shownSpeed) }
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
