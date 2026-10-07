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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
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
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.FeeRate
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetHost
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.sheets.SendRoute
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.OnchainFeeUi
import to.bitkit.viewmodels.SendMethod
import to.bitkit.viewmodels.SendUiState
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
    fun initialOnchainSubscriptionShowsFeeBeforeConfirmation() {
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
                        initialShowDetails = true,
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("SendConfirmAssetButton").assertIsDisplayed()
        composeTestRule.onNodeWithText("422", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("SendConfirmToggleDetails").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swipe To Subscribe & Pay").assertIsDisplayed()
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
}
