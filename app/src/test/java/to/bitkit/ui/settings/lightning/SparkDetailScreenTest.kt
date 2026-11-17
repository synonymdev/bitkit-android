package to.bitkit.ui.settings.lightning

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
import to.bitkit.repositories.SparkDeposit
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SparkDetailScreenTest {
    private companion object {
        const val TXID = "8a6716ce0a1b2c3d4e5f60718293a4b5c6d7e8f9011223344556677848ace8f6"
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `a claimed deposit shows its claim fee and opens its transaction`() {
        val opened = mutableListOf<String>()
        setDeposit(SparkDepositUi(SparkDeposit(TXID, 35_000uL, isClaimed = true, feeSats = 312uL)), opened::add)

        composeRule.onNodeWithText("Deposit tx").assertExists()
        composeRule.onNodeWithText("8a6716ce…48ace8f6").assertExists()
        composeRule.onNodeWithText("Claim fee").assertExists()
        composeRule.onNodeWithText("Status").assertDoesNotExist()
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())
        composeRule.onNodeWithTag("SparkDeposit").performClick()

        assertEquals(listOf(TXID), opened)
    }

    @Test
    fun `an unclaimed deposit shows its confirmations up to three`() {
        setDeposit(SparkDepositUi(SparkDeposit(TXID, 15_000uL, isClaimed = false), confirmations = 5u))

        composeRule.onNodeWithText("Status").assertExists()
        composeRule.onNodeWithText("3/3 confirmations").assertExists()
        composeRule.onNodeWithText("Claim fee").assertDoesNotExist()
    }

    @Test
    fun `an unclaimed deposit with unknown confirmations shows as pending`() {
        setDeposit(SparkDepositUi(SparkDeposit(TXID, 15_000uL, isClaimed = false), confirmations = null))

        composeRule.onNodeWithText("Status").assertExists()
        composeRule.onNodeWithText("Pending").assertExists()
        composeRule.onNodeWithText("Claim fee").assertDoesNotExist()
    }

    @Test
    fun `the bar reads no limit while spark receives`() {
        setBar(isReceiving = true)

        composeRule.onNodeWithText("no limit").assertExists()
    }

    @Test
    fun `the bar claims no receiving while spark cannot receive`() {
        setBar(isReceiving = false)

        composeRule.onNodeWithText("no limit").assertDoesNotExist()
    }

    private fun setDeposit(depositUi: SparkDepositUi, onOpenTx: (String) -> Unit = {}) {
        composeRule.setContent {
            AppThemeSurface {
                SparkDepositRows(depositUi = depositUi, onOpenTx = onOpenTx)
            }
        }
    }

    private fun setBar(isReceiving: Boolean) {
        composeRule.setContent {
            AppThemeSurface {
                LightningChannel(
                    capacity = 580_000L,
                    localBalance = 580_000L,
                    remoteBalance = 0L,
                    status = ChannelStatusUi.OPEN,
                    showReceiving = isReceiving,
                    receivingUnlimited = isReceiving,
                )
            }
        }
    }
}
