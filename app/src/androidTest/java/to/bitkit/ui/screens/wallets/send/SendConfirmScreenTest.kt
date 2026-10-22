@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.wallets.send

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.synonym.bitkitcore.FeeRates
import com.synonym.paykit.PaymentRequestLifecycleState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.FeeRate
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.models.PubkyProfile
import to.bitkit.models.TransactionSpeed
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.screens.subscriptions.SubscriptionFirstPaymentProgress
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.OnchainFeeUi
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@HiltAndroidTest
@ComposeUi
@OptIn(ExperimentalMaterial3Api::class)
class SendConfirmScreenTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @get:Rule
    val composeTestRule = createComposeRule()

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun preparingRequestShowsSavedMetadataUntilConfirmationIsReady() {
        val request = PaykitPaymentRequest(
            paymentRequestId = "preparing",
            counterparty = "requester",
            amount = PaykitAmount(PaykitAsset.BTC, 5_000u),
            paymentReference = "test-reference",
            note = "Dinner",
            expiresAt = null,
            acceptedPaymentEndpointIdentifiers = listOf("bitcoin"),
        )
        val contact = PubkyProfile.placeholder(request.counterparty).copy(name = "Coffee House")
        val preparation = mutableStateOf<PaykitPaymentRequest?>(request)
        val state = mutableStateOf(
            SendUiState(
                amount = 99_000u,
                isAmountInputValid = true,
                payMethod = SendMethod.LIGHTNING,
                isInitialSubscriptionPayment = true,
                initialSubscriptionPaymentAutoStartPending = true,
                paymentRequestNote = "Stale note",
            ),
        )
        var paymentAttempts = 0
        var dismissCount = 0
        var visibleCount = 0
        lateinit var sheetState: SheetState
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    SheetHost(
                        shouldExpand = true,
                        visibilityKey = Sheet.Send(SendRoute.Confirm, preparingRequest = preparation.value),
                        onVisible = { visibleCount++ },
                        onDismiss = { dismissCount++ },
                        sheetState = sheetState,
                        sheets = {
                            SendConfirmContent(
                                uiState = state.value,
                                isNodeRunning = preparation.value == null,
                                isLoading = false,
                                showBiometrics = false,
                                preparingRequest = preparation.value,
                                preparingContact = contact,
                                onSwipeToConfirm = { paymentAttempts++ },
                                modifier = Modifier.sheetHeight()
                            )
                        },
                        content = { Box(Modifier.fillMaxSize()) },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestConfirm").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestFrom").assertTextEquals("Coffee House")
        composeTestRule.onNodeWithTag("PaymentRequestFor").assertTextEquals("Dinner")
        composeTestRule.onNodeWithTag("PaymentRequestPreparing").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").assertDoesNotExist()
        composeTestRule.onNodeWithText("Stale note").assertDoesNotExist()
        composeTestRule.onNodeWithTag("PaymentRequestPreparing").performTouchInput { swipeRight() }
        composeTestRule.runOnIdle {
            assertEquals(0, paymentAttempts)
            assertEquals(SheetValue.Expanded, sheetState.currentValue)
            assertEquals(1, visibleCount)
            state.value = SendUiState(
                amount = request.amount.atomic,
                isPaymentRequest = true,
                isAmountInputValid = true,
                contactPaymentProfile = contact,
                paymentRequestNote = request.note,
                incomingPaymentRequestId = request.id,
            )
            preparation.value = null
        }

        composeTestRule.onNodeWithTag("PaymentRequestPreparing").assertDoesNotExist()
        composeTestRule.onNodeWithTag("PaymentRequestConfirm").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestFrom").assertTextEquals("Coffee House")
        composeTestRule.onNodeWithTag("PaymentRequestFor").assertTextEquals("Dinner")
        composeTestRule.onNodeWithTag("GRAB").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(SheetValue.Expanded, sheetState.currentValue)
            assertEquals(SheetValue.Expanded, sheetState.targetValue)
            assertEquals(2, visibleCount)
            assertEquals(0, dismissCount)
        }
    }

    @Test
    fun preparingUsdtRequestUsesUsdtAccentAndCannotPay() {
        val request = PaykitPaymentRequest(
            paymentRequestId = "preparing-usdt",
            counterparty = "requester",
            amount = PaykitAmount(PaykitAsset.USDT, 5_000_000u),
            paymentReference = "test-reference",
            expiresAt = null,
            acceptedPaymentEndpointIdentifiers = listOf(MethodId.UsdtArbitrum.rawValue),
        )
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    SendConfirmContent(
                        uiState = SendUiState(payMethod = SendMethod.LIGHTNING),
                        isNodeRunning = false,
                        isLoading = false,
                        showBiometrics = false,
                        preparingRequest = request,
                        modifier = Modifier.sheetHeight()
                    )
                }
            }
        }
        val preparing = composeTestRule.onNodeWithTag("PaymentRequestPreparing")
        preparing.assertIsDisplayed().assertIsNotEnabled()
        val pixels = preparing.captureToImage().toPixelMap()
        assertTrue((0 until pixels.width).any { x ->
            (0 until pixels.height).any { y -> pixels[x, y] == Colors.Usdt }
        })
    }

    @Test
    fun initialOnchainSubscriptionKeepsReviewLayoutWhilePaying() {
        val startsAt = Clock.System.now()
        val subscription = PaykitSubscription(
            paymentRequestId = "subscription-id",
            counterparty = "payee",
            amount = PaykitAmount(PaykitAsset.BTC, 3_000uL),
            paymentReference = "subscription",
            pricing = PaykitRequestPricing(),
            note = "Journey Sub",
            createdAt = startsAt,
            proposalExpiresAt = null,
            recurrence = PaykitSubscriptionRecurrence(
                every = 1,
                unit = PaykitRecurrenceUnit.Month,
                startsAt = startsAt,
                anchor = startsAt,
                endsAt = null,
            ),
            metadata = PaykitSubscriptionMetadata(description = null, benefits = emptyList()),
            acceptedPaymentEndpointIdentifiers = listOf("btc-regtest-p2wpkh"),
            lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            paidPeriods = emptyList(),
        )
        val state = SendUiState(
            amount = 3_000u,
            payMethod = SendMethod.ONCHAIN,
            isAmountInputValid = true,
            isInitialSubscriptionPayment = true,
            initialSubscriptionPaymentAutoStartPending = true,
            onchainFeeUi = OnchainFeeUi(rate = FeeRate.NORMAL, sats = 422),
        )
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    SendConfirmContent(
                        uiState = state,
                        isNodeRunning = true,
                        isLoading = false,
                        showBiometrics = false,
                        autoPayContent = {
                            SubscriptionFirstPaymentProgress(
                                subscription = subscription,
                                contact = PubkyProfile.placeholder("payee"),
                            )
                        },
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Journey Sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("GRAB").assertIsDisplayed()
        composeTestRule.onNodeWithTag("GRAB").performTouchInput { swipeRight() }
        composeTestRule.onNodeWithText("Journey Sub").assertIsDisplayed()
        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").assertDoesNotExist()
        composeTestRule.onNodeWithText("Swipe To Subscribe & Pay").assertDoesNotExist()
    }

    @Test
    fun initialOnchainSubscriptionShowsProgressWhenSubscriptionIsMissing() {
        val state = SendUiState(
            amount = 3_000u,
            payMethod = SendMethod.ONCHAIN,
            isAmountInputValid = true,
            isInitialSubscriptionPayment = true,
            initialSubscriptionPaymentAutoStartPending = true,
            onchainFeeUi = OnchainFeeUi(rate = FeeRate.NORMAL, sats = 422),
        )
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(LocalInspectionMode provides true) {
                    SendConfirmContent(
                        uiState = state,
                        isNodeRunning = true,
                        isLoading = false,
                        showBiometrics = false,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("GRAB").assertDoesNotExist()
        composeTestRule.onNodeWithText("Swipe To Subscribe & Pay").assertDoesNotExist()
    }

    @Test
    fun paymentRequestKeepsAmountAndSwipeVisibleOnCompactScreen() {
        val state = SendUiState(
            amount = 1_753u,
            payMethod = SendMethod.ONCHAIN,
            isAmountInputValid = true,
            isPaymentRequest = true,
            contactPaymentProfile = PubkyProfile.placeholder("requester").copy(name = "Popup requester"),
            paymentRequestNote = "A long invoice note. ".repeat(13).take(256),
            onchainFeeUi = OnchainFeeUi(rate = FeeRate.NORMAL, sats = 141),
        )
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.3f),
                ) {
                    Box(modifier = Modifier.size(width = 360.dp, height = 400.dp)) {
                        SendConfirmContent(
                            uiState = state,
                            isNodeRunning = true,
                            isLoading = false,
                            showBiometrics = false,
                        )
                    }
                }
            }
        }

        val amount = composeTestRule.onNodeWithTag("ReviewAmount")
        val swipe = composeTestRule.onNodeWithTag("GRAB")
        amount.assertIsDisplayed()
        swipe.assertIsDisplayed()
        val amountBounds = amount.getUnclippedBoundsInRoot()
        val swipeBounds = swipe.getUnclippedBoundsInRoot()

        val contentBounds = composeTestRule.onNodeWithTag("SendConfirmContent").getUnclippedBoundsInRoot()
        composeTestRule.onNodeWithTag("PaymentRequestFrom").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestFor").performScrollTo().assertIsDisplayed()
        for (tag in listOf("PaymentRequestFrom", "PaymentRequestFor")) {
            val bounds = composeTestRule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue(bounds.top >= contentBounds.top)
            assertTrue(bounds.bottom <= contentBounds.bottom)
        }

        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").performClick()
        composeTestRule.onNodeWithTag("PaymentRequestInvoiceNote").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("SendConfirmContent").performTouchInput { swipeUp() }

        amount.assertIsDisplayed()
        swipe.assertIsDisplayed()
        assertEquals(amountBounds, amount.getUnclippedBoundsInRoot())
        assertEquals(swipeBounds, swipe.getUnclippedBoundsInRoot())
    }

    @Test
    fun customFeeShowsTheConfirmationTimeOfItsTier() {
        composeTestRule.setContent {
            AppThemeSurface {
                SendConfirmContent(
                    uiState = SendUiState(
                        amount = 25_000u,
                        address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                        isAmountInputValid = true,
                        payMethod = SendMethod.ONCHAIN,
                        speed = TransactionSpeed.Custom(4u),
                        feeRates = FeeRates(fast = 2u, mid = 1u, slow = 1u),
                        onchainFeeUi = OnchainFeeUi(rate = FeeRate.CUSTOM),
                    ),
                    isNodeRunning = true,
                    isLoading = false,
                    showBiometrics = false,
                    modifier = Modifier.sheetHeight(),
                )
            }
        }

        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").performClick()
        composeTestRule.onNodeWithText("±10-20 minutes").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Depends on the fee").assertDoesNotExist()
    }

    @Test
    fun merchantRequestLabelsThePayeeAsTo() {
        val seller = PubkyProfile.placeholder("seller").copy(name = "Corner Shop")
        val state = mutableStateOf(
            SendUiState(
                amount = 25_000u,
                address = "bcrt1qkgfgyxyqhvkdqh04sklnzxphmcds6vft6y7h0r",
                isAmountInputValid = true,
                payMethod = SendMethod.ONCHAIN,
                isPaymentRequest = true,
                isMerchantPaymentRequest = true,
                contactPaymentProfile = seller,
                paymentRequestNote = "Pubky Shop order 1a2b3c4d",
            )
        )
        composeTestRule.setContent {
            AppThemeSurface {
                SendConfirmContent(
                    uiState = state.value,
                    isNodeRunning = true,
                    isLoading = false,
                    showBiometrics = false,
                    modifier = Modifier.sheetHeight(),
                )
            }
        }

        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").performClick()
        composeTestRule.onNodeWithText("To", ignoreCase = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Contact", ignoreCase = true).assertDoesNotExist()

        composeTestRule.runOnIdle { state.value = state.value.copy(isMerchantPaymentRequest = false) }
        composeTestRule.onNodeWithText("Contact", ignoreCase = true).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("To", ignoreCase = true).assertDoesNotExist()
    }
}
