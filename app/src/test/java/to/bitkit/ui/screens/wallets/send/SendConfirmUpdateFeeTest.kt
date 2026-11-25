package to.bitkit.ui.screens.wallets.send

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.repositories.MoveToSparkQuote
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class SendConfirmUpdateFeeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    @Test
    fun `a payment that needs an update shows its fee without opening the details`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    SendConfirmContent(
                        uiState = SendUiState(
                            amount = 50_000u,
                            isAmountInputValid = true,
                            payMethod = SendMethod.LIGHTNING,
                            decodedInvoice = INVOICE,
                            lightningFeeSats = 12,
                            sparkUpdateQuote = MoveToSparkQuote("lnbcrt1spark", amountSats = 29_967uL, feeSats = 2uL),
                        ),
                        isNodeRunning = true,
                        isLoading = false,
                        showBiometrics = false,
                        initialShowDetails = false,
                        modifier = Modifier.sheetHeight()
                    )
                }
            }
        }

        composeRule.onNodeWithTag("ReviewUpdateFee").assertIsDisplayed()
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    @Test
    fun `a running swipe hides back and keeps the amount as swiped`() {
        val events = mutableListOf<SendEvent>()
        val backs = mutableListOf<Unit>()
        composeRule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                AppThemeSurface {
                    SendConfirmContent(
                        uiState = SendUiState(
                            amount = 50_000u,
                            isAmountInputValid = true,
                            payMethod = SendMethod.LIGHTNING,
                            decodedInvoice = INVOICE.copy(amountSatoshis = 0u),
                            sparkUpdateQuote = MoveToSparkQuote("lnbcrt1spark", amountSats = 29_967uL, feeSats = 2uL),
                        ),
                        isNodeRunning = true,
                        isLoading = true,
                        showBiometrics = false,
                        isLocked = true,
                        onBack = { backs += Unit },
                        onEvent = { events += it },
                        modifier = Modifier.sheetHeight()
                    )
                }
            }
        }

        composeRule.onNodeWithTag("NavigationBack").assertDoesNotExist()
        composeRule.onNodeWithTag("ReviewAmount").performClick()

        assertEquals(emptyList(), events)
        assertEquals(emptyList(), backs)
    }

    @Config(sdk = [34])
    @Test
    fun `a swipe with nothing running for it frees the confirm after a second`() {
        var isSwipeRunning by mutableStateOf(true)
        var isLocked = false
        composeRule.setContent {
            isLocked = rememberConfirmLock(isLoading = true, isSwipeRunning = isSwipeRunning, uiState = SendUiState())
        }
        advanceBy(SWIPE_IDLE_RELEASE * 2)
        assertTrue(isLocked)

        composeRule.runOnUiThread { isSwipeRunning = false }
        advanceBy(SWIPE_IDLE_RELEASE / 2)
        assertTrue(isLocked)
        advanceBy(SWIPE_IDLE_RELEASE)

        assertFalse(isLocked)
    }

    @Config(sdk = [34])
    @Test
    fun `a pending pay confirmation with nothing running stays locked`() {
        var isLocked = false
        composeRule.setContent {
            isLocked = rememberConfirmLock(
                isLoading = true,
                isSwipeRunning = false,
                uiState = SendUiState(shouldConfirmPay = true),
            )
        }
        advanceBy(5.seconds)

        assertTrue(isLocked)
    }

    @Config(sdk = [34])
    @Test
    fun `a new run after the idle release locks from its first composition`() {
        var isSwipeRunning by mutableStateOf(false)
        val composed = mutableListOf<Pair<Boolean, Boolean>>()
        composeRule.setContent {
            val locked = rememberConfirmLock(isLoading = true, isSwipeRunning = isSwipeRunning, uiState = SendUiState())
            composed += isSwipeRunning to locked
        }
        advanceBy(SWIPE_IDLE_RELEASE * 2)
        assertEquals(false to false, composed.last())
        val released = composed.size

        composeRule.runOnUiThread { isSwipeRunning = true }
        advanceBy(SWIPE_IDLE_RELEASE / 4)

        val afterFlip = composed.drop(released)
        assertTrue(afterFlip.isNotEmpty())
        assertTrue(afterFlip.all { (running, locked) -> !running || locked }, "compositions after flip: $afterFlip")
    }

    private fun advanceBy(duration: Duration) {
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(duration.inWholeMilliseconds)
        composeRule.waitForIdle()
    }

    private companion object {
        val INVOICE = LightningInvoice(
            bolt11 = "lnbcrt1p5frwyedq2gf5hg6mfwsnp4qdkkcte90vc7c5z6z72uu5p2schwkmlx9j704tuwm2z59wfgku46xpp56yfmwmfxtl",
            paymentHash = ByteArray(0),
            amountSatoshis = 50_000u,
            timestampSeconds = 0u,
            expirySeconds = 3600u,
            isExpired = false,
            networkType = NetworkType.REGTEST,
            payeeNodeId = null,
            description = "Coffee",
        )
    }
}
