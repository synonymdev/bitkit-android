package to.bitkit.ui.screens.wallets.usdt

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.UsdtBridgeProvider
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtQuote
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.theme.AppThemeSurface
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@HiltAndroidTest
@ComposeUi
class UsdtPaymentContentTest {
    private val originalLocale = Locale.getDefault()
    private val quote = UsdtQuote(
        id = "bridge",
        recipient = "0x1111111111111111111111111111111111111111",
        destination = UsdtDestination.POLYGON,
        bridgeProvider = UsdtBridgeProvider.ORCHESTRA,
        amount = 3_000_000u,
        receivedAmount = 2_990_000u,
        maximumFee = 1_470_000u,
        expiresAt = 100u,
    )

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        Locale.setDefault(Locale.US)
        hiltRule.inject()
    }

    @After
    fun teardown() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun failedRequestQuoteKeepsConfirmationAndOffersRetryWithoutTheKeypad() {
        var retries = 0
        composeTestRule.setContent {
            Content(
                UsdtSendState(error = to.bitkit.R.string.usdt__error_expired), UsdtPage.AMOUNT,
                amountEditable = false,
                isPaymentRequest = true,
                onReview = { retries++ },
            )
        }
        composeTestRule.onNodeWithTag("UsdtConfirmLoading").assertIsDisplayed()
        composeTestRule.onNodeWithTag("NDecimal").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtRetryQuote").performClick()
        composeTestRule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun lockedReviewAmountKeepsReviewOpenWhileBackAndEditableAmountsStillNavigate() {
        val editable = mutableStateOf(false)
        var pageChanges = 0
        var backPresses = 0
        val payment = quote.copy(destination = UsdtDestination.ARBITRUM, bridgeProvider = null)
        composeTestRule.setContent {
            Content(
                UsdtSendState(quote = payment), UsdtPage.AMOUNT,
                amountEditable = editable.value,
                onPageChange = { pageChanges++ },
                onBack = { backPresses++ },
            )
        }
        composeTestRule.onNodeWithText("3.00").performClick()
        composeTestRule.onNodeWithTag("UsdtConfirm").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(0, pageChanges)
            assertEquals(0, backPresses)
        }
        composeTestRule.onNodeWithTag("NavigationBack").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, backPresses)
            editable.value = true
        }
        SystemClock.sleep(600)
        composeTestRule.onNodeWithText("3.00").performClick()
        composeTestRule.runOnIdle { assertEquals(1, pageChanges) }
    }

    @Test
    fun decimalKeyUsesDotInCommaRegionsWithoutChangingInputEncoding() {
        Locale.setDefault(Locale.GERMANY)
        val amount = mutableStateOf("5")
        composeTestRule.setContent {
            AppThemeSurface {
                Column {
                    UsdtReceiveAmountContent(
                        amount = amount.value,
                        allowEmpty = false,
                        busy = false,
                        error = null,
                        limits = null,
                        onAmount = { amount.value = it },
                        onContinue = {},
                    )
                }
            }
        }
        composeTestRule.onNodeWithText(".").assertIsDisplayed()
        composeTestRule.onNodeWithTag("NDecimal").performClick()
        composeTestRule.onNodeWithTag("N5").performClick()
        composeTestRule.onNodeWithText("5.5").assertIsDisplayed()
        composeTestRule.runOnIdle { assertEquals("5.5", amount.value) }
    }

    @Test
    fun walletDistinguishesUnknownAndConfirmedBalancesSilentlyAndKeepsPrivacy() {
        Locale.setDefault(Locale.GERMANY)
        val wallet = mutableStateOf(UsdtWalletState())
        val hidden = mutableStateOf(false)
        composeTestRule.setContent {
            AppThemeSurface {
                UsdtWalletContent(
                    wallet = wallet.value,
                    hideBalance = hidden.value,
                    onReceive = {}, onSend = {}, onBack = {},
                )
            }
        }
        composeTestRule.onNodeWithText("—").assertIsDisplayed()
        composeTestRule.onNodeWithText("0.00").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtHistorySync").assertDoesNotExist()
        composeTestRule.runOnIdle { wallet.value = wallet.value.copy(balance = 0uL) }
        composeTestRule.onNodeWithText("0.00").assertIsDisplayed()
        composeTestRule.onNodeWithText("—").assertDoesNotExist()
        composeTestRule.runOnIdle { wallet.value = wallet.value.copy(balance = 1_234_567_890uL) }
        composeTestRule.onNodeWithText("1,234.57").assertIsDisplayed()
        composeTestRule.runOnIdle { hidden.value = true }
        composeTestRule.onNodeWithText("1,234.57").assertDoesNotExist()
    }

    @Test
    fun largeHeadersAndNetworkLabelsRemainReadable() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                AppThemeSurface {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                        AppTopBar(titleText = "Cross-Network Deposits", onBackClick = {})
                        SheetTopBar(titleText = "Select Network", onBack = {})
                        UsdtNetworkButton(
                            UsdtDestination.BSC,
                            {},
                            persistentListOf(UsdtDestination.BSC, UsdtDestination.ARBITRUM)
                        )
                    }
                }
            }
        }
        listOf("Cross-Network Deposits", "Select Network", "BNB Smart Chain").forEach { label ->
            val node = composeTestRule.onNodeWithText(label).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertFalse(layout.didOverflowHeight, "Clipped height for $label")
            repeat(layout.lineCount) { line ->
                assertFalse(layout.isLineEllipsized(line), "Ellipsized $label")
                assertTrue(
                    layout.getLineRight(line) - layout.getLineLeft(line) <= layout.size.width + 1f,
                    "Clipped width for $label"
                )
            }
        }
    }

    @Test
    fun bridgeAmountsAndNetworksAreInExpandedDetails() {
        composeTestRule.setContent { Content(UsdtSendState(quote = quote), UsdtPage.AMOUNT) }
        val total = composeTestRule.onNodeWithTag("UsdtMaximumTotal")
        val swipe = composeTestRule.onNodeWithTag("UsdtConfirm")
        composeTestRule.onNodeWithText("YOU SEND").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtRecipientAmount").assertDoesNotExist()
        total.assertDoesNotExist()
        swipe.assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtReviewDetails").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Arbitrum One").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("UsdtRecipientAmount").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("±2.99").assertIsDisplayed()
        total.assertIsDisplayed()
        composeTestRule.onNodeWithText("4.47").assertIsDisplayed()
        composeTestRule.onNodeWithText("Polygon").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("All fees are paid in USDT. Final fee may be lower.").performScrollTo().assertIsDisplayed()
        swipe.assertIsDisplayed()
        SystemClock.sleep(600)
        composeTestRule.onNodeWithTag("UsdtReviewDetails").performScrollTo().performClick()
        total.assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtRecipientAmount").assertDoesNotExist()
    }

    @Test
    fun recipientAddressCanBeFullyReviewed() {
        composeTestRule.setContent { Content(UsdtSendState(quote = quote), UsdtPage.AMOUNT) }
        composeTestRule.onNodeWithTag("UsdtReviewDetails").performScrollTo().performClick()
        val address = composeTestRule.onNodeWithTag("UsdtReviewAddress")
        address.performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        address.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().isLineEllipsized(0))
        address.performClick()
        composeTestRule.waitForIdle()
        layouts.clear()
        address.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().lineCount > 1)
        assertFalse(layouts.single().hasVisualOverflow)
        composeTestRule.onNodeWithTag("UsdtConfirm").assertIsDisplayed()
    }

    @Test
    fun closingPaymentDoesNotOpenRecipientEntry() {
        val page = mutableStateOf(UsdtPage.AMOUNT)
        val state = mutableStateOf(UsdtSendState(quote = quote, submitted = true))
        composeTestRule.setContent {
            Content(state.value, page.value) {
                state.value = UsdtSendState()
                page.value = UsdtPage.WALLET
            }
        }
        composeTestRule.onNodeWithText("Details").performClick()
        composeTestRule.onNodeWithTag("UsdtManual").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtPaste").assertDoesNotExist()
    }

    @Test
    fun pendingDirectPaymentStaysSubmittedUntilSuccess() {
        assertCompletionTransition(UsdtDestination.ARBITRUM, UsdtTransferStatus.PENDING)
    }

    @Test
    fun bridgeStaysSubmittedUntilDeliverySuccess() {
        assertCompletionTransition(UsdtDestination.POLYGON, UsdtTransferStatus.BRIDGING)
    }

    @Test
    fun immediateConfirmationShowsCompletionBeforeSuccess() {
        val payment = quote.copy(destination = UsdtDestination.ARBITRUM)
        val state = mutableStateOf(UsdtSendState(quote = payment, submitted = true, busy = true))
        val wallet = UsdtWalletState(transfers = persistentListOf(transfer(payment, UsdtTransferStatus.CONFIRMED)))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent { Content(state.value, UsdtPage.AMOUNT, wallet = wallet) }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("UsdtConfirm").assertIsDisplayed()
        composeTestRule.runOnUiThread { state.value = state.value.copy(busy = false) }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onNodeWithTag("UsdtConfirm").assertIsDisplayed()
        composeTestRule.onNodeWithTag("NavigationBack").assertDoesNotExist()
        composeTestRule.onNodeWithText("Payment submitted").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtSendSuccess").assertDoesNotExist()
        composeTestRule.mainClock.advanceTimeBy(700)
        composeTestRule.onNodeWithTag("UsdtConfirm").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtSendSuccess").assertIsDisplayed()
    }

    @Test
    fun returningToConfirmedPaymentDoesNotReopenReview() {
        val showDetails = mutableStateOf(false)
        val payment = transfer(quote, UsdtTransferStatus.BRIDGING)
        val wallet = mutableStateOf(UsdtWalletState(transfers = persistentListOf(payment)))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            if (!showDetails.value) {
                Content(UsdtSendState(quote = quote, submitted = true), UsdtPage.AMOUNT, wallet = wallet.value) {
                    showDetails.value = true
                }
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("Details").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("Payment submitted").assertDoesNotExist()
        composeTestRule.runOnUiThread {
            wallet.value = wallet.value.copy(transfers = persistentListOf(payment.copy(status = UsdtTransferStatus.CONFIRMED)))
            showDetails.value = false
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onNodeWithTag("UsdtConfirm").assertDoesNotExist()
        composeTestRule.onNodeWithTag("NavigationBack").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtSendSuccess").assertDoesNotExist()
        composeTestRule.mainClock.advanceTimeBy(700)
        composeTestRule.onNodeWithTag("UsdtSendSuccess").assertIsDisplayed()
    }

    private fun assertCompletionTransition(destination: UsdtDestination, pendingStatus: UsdtTransferStatus) {
        val payment = quote.copy(destination = destination)
        val transfer = transfer(payment, pendingStatus)
        val wallet = mutableStateOf(UsdtWalletState(transfers = persistentListOf(transfer)))
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.setContent {
            Content(UsdtSendState(quote = payment, submitted = true), UsdtPage.AMOUNT, wallet = wallet.value)
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("Payment submitted").assertIsDisplayed()
        composeTestRule.onNodeWithTag("NavigationBack").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtConfirm").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtSendSuccess").assertDoesNotExist()
        composeTestRule.runOnUiThread {
            wallet.value = wallet.value.copy(transfers = persistentListOf(transfer.copy(status = UsdtTransferStatus.CONFIRMED)))
        }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onNodeWithText("Payment submitted").assertIsDisplayed()
        composeTestRule.onNodeWithTag("NavigationBack").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtConfirm").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtSendSuccess").assertDoesNotExist()
        composeTestRule.mainClock.advanceTimeBy(700)
        composeTestRule.onNodeWithTag("UsdtConfirm").assertDoesNotExist()
        composeTestRule.onNodeWithTag("UsdtSendSuccess").assertIsDisplayed()
    }

    private fun transfer(payment: UsdtQuote, status: UsdtTransferStatus) = UsdtTransfer(
        id = payment.id, sender = null, txHash = null, userOperationHash = null,
        bridgeGuid = null, orchestra = null, recipient = payment.recipient,
        destination = payment.destination, amount = payment.amount, receivedAmount = payment.receivedAmount,
        fee = null, isIncoming = false, status = status, timestamp = 1u,
    )

    @Composable
    private fun Content(
        state: UsdtSendState,
        page: UsdtPage,
        wallet: UsdtWalletState = UsdtWalletState(),
        amountEditable: Boolean = true,
        isPaymentRequest: Boolean = false,
        onReview: () -> Unit = {},
        onPageChange: (UsdtPage) -> Unit = {},
        onBack: () -> Unit = {},
        onDetails: () -> Unit = {},
    ) {
        AppThemeSurface {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                Box(modifier = Modifier.size(width = 360.dp, height = 600.dp)) {
                    UsdtPaymentContent(
                        wallet = wallet,
                        state = state,
                        page = page,
                        recipient = quote.recipient,
                        amount = "3",
                        destination = state.quote?.destination ?: quote.destination,
                        onRecipientChange = {},
                        onAmountChange = {},
                        onRecipientContinue = {},
                        onPageChange = onPageChange,
                        onBack = onBack,
                        onReview = onReview,
                        onConfirm = {},
                        onDone = {},
                        onDetails = onDetails,
                        amountEditable = amountEditable,
                        isPaymentRequest = isPaymentRequest,
                    )
                }
            }
        }
    }
}
