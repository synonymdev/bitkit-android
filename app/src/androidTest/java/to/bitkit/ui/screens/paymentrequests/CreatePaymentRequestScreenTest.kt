@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.BITCOIN_SYMBOL
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.PubkyProfile
import to.bitkit.models.USD_SYMBOL
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.PaykitBillingPeriod
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.LocalCurrencies
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.test.assertEquals

@ComposeUi
class CreatePaymentRequestScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun detailsShowsAmountNoteExpiryAndSend() {
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestDetailsContent(
                    initialDraft = draft,
                    contact = PubkyProfile.placeholder(target.publicKey),
                    isCreating = false,
                    onBack = {},
                    onEditAmount = {},
                    onSend = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestNote").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestExpiryWeek").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestSend").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestNumberPad").assertDoesNotExist()
    }

    @Test
    fun amountShowsNumberPadAndContinue() {
        var currencies by mutableStateOf(CurrencyState())
        val amountInputHandler = object : AmountInputHandler by AmountInputHandler.stub() {
            override suspend fun switchUnit(unit: PrimaryDisplay) = unit.not().also {
                currencies = currencies.copy(primaryDisplay = it)
            }
        }
        val amountInputViewModel = AmountInputViewModel(amountInputHandler)
        composeTestRule.setContent {
            AppThemeSurface {
                CompositionLocalProvider(
                    LocalInspectionMode provides true,
                    LocalCurrencies provides currencies,
                ) {
                    PaymentRequestAmountContent(
                        amountInputViewModel = amountInputViewModel,
                        initialDraft = draft,
                        contact = PubkyProfile.placeholder(target.publicKey),
                        onBack = {},
                        onContinue = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestAmountField").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestNumberPad").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestAmountContinue").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestNote").assertDoesNotExist()
        composeTestRule.onAllNodesWithText(USD_SYMBOL, substring = true, useUnmergedTree = true).assertCountEquals(1)

        composeTestRule.onNodeWithTag("PaymentRequestAmountUnit").performClick()

        composeTestRule.onAllNodesWithText(BITCOIN_SYMBOL, substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    @Test
    fun recipientShowsEligibleContactAndAdvancesOnSelection() {
        var selectedTarget: PaykitPaymentRequestTarget? = null
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestRecipientContent(
                    targets = persistentListOf(target),
                    contacts = persistentListOf(PubkyProfile.placeholder(target.publicKey)),
                    onBack = {},
                    onPaste = { target.publicKey },
                    onSelected = { selectedTarget = it },
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestContact${target.publicKey}").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestRecipientSearch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestRecipientPaste", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestSend").assertDoesNotExist()
        composeTestRule.onNodeWithTag("PaymentRequestContact${target.publicKey}").performClick()
        assertEquals(target, selectedTarget)

        composeTestRule.onNodeWithTag("PaymentRequestRecipientSearch").performTextInput("not this contact")

        composeTestRule.onNodeWithTag("PaymentRequestContact${target.publicKey}").assertDoesNotExist()
        composeTestRule.onNodeWithText("No matching saved contact with a private connection.").assertIsDisplayed()
    }

    @Test
    fun sentShowsSuccessSurface() {
        val contact = PubkyProfile.forDisplay(target.publicKey, "Anna", imageUrl = null)
        val createdRequest = request.copy(note = null, deliveryStatus = PaykitPaymentRequestDeliveryStatus.Queued)
        val sentRequest = createdRequest.copy(deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent)
        val proofRequest = sentRequest.copy(lifecycleState = PaymentRequestLifecycleState.PROOF_SUBMITTED)
        var history by mutableStateOf(persistentListOf<PaykitPaymentRequest>())
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestSentContent(
                    request = createdRequest,
                    history = history,
                    contact = contact,
                    onDone = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestSent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestSentCheck").assertIsDisplayed()
        composeTestRule.onNodeWithText("PAYMENT REQUESTED").assertIsDisplayed()
        composeTestRule.onNodeWithText("Anna").assertIsDisplayed()
        composeTestRule.onNodeWithText("Queued for delivery").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your payment request is queued and will send automatically").assertIsDisplayed()
        composeTestRule.onNodeWithText("You have sent a payment request").assertDoesNotExist()

        composeTestRule.runOnIdle { history = persistentListOf(sentRequest) }

        composeTestRule.onNodeWithText("You have sent a payment request").assertIsDisplayed()
        composeTestRule.onNodeWithText("Your payment request is queued and will send automatically").assertDoesNotExist()
        composeTestRule.onNodeWithText("Waiting for payment").assertIsDisplayed()

        composeTestRule.runOnIdle { history = persistentListOf(proofRequest) }

        composeTestRule.onNodeWithText("Proof submitted").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for payment").assertDoesNotExist()
        composeTestRule.onNodeWithText("PAYMENT REQUESTED").assertIsDisplayed()

        composeTestRule.runOnIdle { history = persistentListOf(proofRequest.copy(note = "Dinner")) }

        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Proof submitted").assertDoesNotExist()

        composeTestRule.runOnIdle { history = persistentListOf() }

        composeTestRule.onNodeWithText("Queued for delivery").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dinner").assertDoesNotExist()
    }

    @Test
    fun sentIgnoresHistoryForOtherRequests() {
        val createdRequest = request.copy(note = "", deliveryStatus = PaykitPaymentRequestDeliveryStatus.Queued)
        val proofRequest = createdRequest.copy(lifecycleState = PaymentRequestLifecycleState.PROOF_SUBMITTED)
        var history by mutableStateOf(persistentListOf<PaykitPaymentRequest>())
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestSentContent(
                    request = createdRequest,
                    history = history,
                    contact = null,
                    onDone = {},
                )
            }
        }

        listOf(
            proofRequest.copy(paymentRequestId = "another-request"),
            proofRequest.copy(counterparty = "another-counterparty"),
            proofRequest.copy(direction = PaykitPaymentRequestDirection.Incoming),
            proofRequest.copy(
                billingPeriod = PaykitBillingPeriod(
                    startsAt = Instant.parse("2027-01-15T08:00:00Z"),
                    endsAt = Instant.parse("2027-02-15T08:00:00Z"),
                ),
            ),
        ).forEach { unrelatedRequest ->
            composeTestRule.runOnIdle { history = persistentListOf(unrelatedRequest) }

            composeTestRule.onNodeWithText("Queued for delivery").assertIsDisplayed()
            composeTestRule.onNodeWithText("Proof submitted").assertDoesNotExist()
        }
    }

    private val draft = PaykitPaymentRequestDraft(
        amountSats = 25_000uL,
        note = "Dinner",
        expiresAt = Instant.parse("2027-01-15T09:00:00Z"),
    )

    private val target = PaykitPaymentRequestTarget(
        publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
    )

    private val request = PaykitPaymentRequest(
        paymentRequestId = "payment-request",
        counterparty = target.publicKey,
        amountValue = "0.00025",
        amountSats = draft.amountSats,
        note = draft.note,
        createdAt = Instant.parse("2027-01-15T08:00:00Z"),
        expiresAt = Instant.DISTANT_FUTURE,
        acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
        direction = PaykitPaymentRequestDirection.Outgoing,
    )
}
