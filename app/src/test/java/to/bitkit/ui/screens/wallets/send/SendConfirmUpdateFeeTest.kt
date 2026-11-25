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
import androidx.compose.ui.test.performScrollTo
import com.synonym.bitkitcore.LightningInvoice
import com.synonym.bitkitcore.NetworkType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import to.bitkit.repositories.MoveToSparkQuote
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

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

    @Config(sdk = [34])
    @Test
    fun `a swipe accepting a prepared fee locks the confirm`() {
        var isLoading by mutableStateOf(false)
        var isLocked = true
        composeRule.setContent {
            isLocked = rememberConfirmLock(
                isLoading = isLoading,
                isSwipeRunning = true,
                uiState = SendUiState(isPreparedFeeReview = true),
            )
        }
        advanceBy(SWIPE_IDLE_RELEASE / 4)
        assertFalse(isLocked)

        composeRule.runOnUiThread { isLoading = true }
        advanceBy(SWIPE_IDLE_RELEASE / 4)

        assertTrue(isLocked)
    }

    @Config(sdk = [34])
    @Test
    fun `a closed biometrics prompt locks again from its first composition`() {
        var showBiometrics by mutableStateOf(true)
        val composed = mutableListOf<Boolean>()
        composeRule.setContent {
            composed += rememberConfirmLock(
                isLoading = true,
                isSwipeRunning = false,
                uiState = SendUiState(),
                showBiometrics = showBiometrics,
            )
        }
        advanceBy(SWIPE_IDLE_RELEASE * 2)
        assertFalse(composed.last())
        val prompted = composed.size

        composeRule.runOnUiThread { showBiometrics = false }
        advanceBy(SWIPE_IDLE_RELEASE / 4)

        val afterClose = composed.drop(prompted)
        assertTrue(afterClose.isNotEmpty())
        assertTrue(afterClose.all { it }, "compositions after close: $afterClose")
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    @Test
    fun `the lock hides back and blocks edits on its own`() {
        val (backs, addTags) = renderConfirm(isLocked = true)

        composeRule.onNodeWithTag("NavigationBack").assertDoesNotExist()
        composeRule.onNodeWithTag("TagsAddSend").performScrollTo().performClick()

        assertEquals(0, addTags.size)
        assertEquals(0, backs.size)
    }

    @Config(sdk = [34], qualifiers = "w411dp-h891dp")
    @Test
    fun `an unlocked confirm takes edits`() {
        val (_, addTags) = renderConfirm(isLocked = false)

        composeRule.onNodeWithTag("TagsAddSend").performScrollTo().performClick()

        assertEquals(1, addTags.size)
    }

    private fun renderConfirm(isLocked: Boolean): Pair<List<Unit>, List<Unit>> {
        val backs = mutableListOf<Unit>()
        val addTags = mutableListOf<Unit>()
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
                        // Off, so only the lock gates the rows that loading also disables
                        isLoading = false,
                        showBiometrics = false,
                        isLocked = isLocked,
                        onBack = { backs += Unit },
                        onClickAddTag = { addTags += Unit },
                        initialShowDetails = true,
                        modifier = Modifier.sheetHeight()
                    )
                }
            }
        }
        // Past the click debounce, which reads the system clock
        ShadowSystemClock.advanceBy(1.seconds.toJavaDuration())
        return backs to addTags
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
