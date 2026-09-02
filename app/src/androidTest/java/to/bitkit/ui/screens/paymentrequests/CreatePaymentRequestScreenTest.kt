@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.AmountInputHandler
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDraft
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AmountInputViewModel
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ComposeUi
class CreatePaymentRequestScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun detailsShowsAmountNoteExpiryAndContinue() {
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestDetailsContent(
                    amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                    initialDraft = draft,
                    onBack = {},
                    onContinue = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestAmountField").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestNote").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestExpiryWeek").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestAmountContinue").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestNumberPad").assertDoesNotExist()

        composeTestRule.onNodeWithTag("PaymentRequestEditAmount").performClick()

        composeTestRule.onNodeWithTag("PaymentRequestNumberPad").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestNote").assertDoesNotExist()
    }

    @Test
    fun detailsWithRecipientShowsCardAndSendRequest() {
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestDetailsContent(
                    amountInputViewModel = AmountInputViewModel(AmountInputHandler.stub()),
                    initialDraft = draft,
                    onBack = {},
                    onContinue = {},
                    recipient = PubkyProfile.placeholder(target.publicKey),
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestSend").assertIsDisplayed()
        composeTestRule.onNodeWithText("Send Request").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestAmountContinue").assertDoesNotExist()
    }

    @Test
    fun recipientShowsEligibleContactAndSendAction() {
        val contacts = PaymentRequestFigmaFixtures.recipientContacts
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestRecipientContent(
                    targets = PaymentRequestFigmaFixtures.recipientTargets,
                    contacts = contacts,
                    isCreating = false,
                    onBack = {},
                    onEditExpiration = {},
                    onPaste = { PaymentRequestFigmaFixtures.anna.publicKey },
                    onSend = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Alex Stronghand").assertIsDisplayed()
        composeTestRule.onNodeWithText("Anna Pleb").assertIsDisplayed()
        composeTestRule.onNodeWithText("Areem Holden").assertIsDisplayed()
        composeTestRule.onNodeWithText("Craig Wrong").assertIsDisplayed()
        composeTestRule.onNodeWithText("John Carvalho").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestRecipientSearch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestContactsHeader").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestEditExpiration").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestRecipientPaste", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestSend").assertIsDisplayed()

        composeTestRule.onNodeWithTag("PaymentRequestRecipientSearch").performTextInput("not this contact")

        composeTestRule.onNodeWithText("Alex Stronghand").assertDoesNotExist()
        composeTestRule.onNodeWithText("No matching saved contact with a private connection.").assertIsDisplayed()
    }

    @Test
    fun recipientFromInvoiceHidesContactsHeader() {
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestRecipientContent(
                    targets = PaymentRequestFigmaFixtures.recipientInvoiceTargets,
                    contacts = PaymentRequestFigmaFixtures.recipientInvoiceContacts,
                    isCreating = false,
                    onBack = {},
                    onEditExpiration = {},
                    onPaste = { PaymentRequestFigmaFixtures.anna.publicKey },
                    onSend = {},
                    showContactsHeader = false,
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestContactsHeader").assertDoesNotExist()
        composeTestRule.onNodeWithText("Alex Stronghand").assertIsDisplayed()
        composeTestRule.onNodeWithText("Anna Pleb").assertIsDisplayed()
        composeTestRule.onNodeWithText("Areem Holden").assertIsDisplayed()
        composeTestRule.onNodeWithText("Craig Wrong").assertIsDisplayed()
        composeTestRule.onNodeWithText("John Carvalho").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Paola Andina").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun sentShowsSuccessSurface() {
        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestSentContent(
                    request = request.copy(deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent),
                    contact = PubkyProfile.placeholder(target.publicKey),
                    onDone = {},
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestSent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestSentCheck").assertIsDisplayed()
        composeTestRule.onNodeWithText("PAYMENT REQUESTED").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dinner").assertIsDisplayed()
    }

    private val draft = PaykitPaymentRequestDraft(
        amountSats = 25_000uL,
        note = "Dinner",
        expiresAt = Instant.parse("2027-01-15T09:00:00Z"),
    )

    private val target = PaykitPaymentRequestTarget(
        publicKey = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
        receiverPath = "bitkit/wallet",
    )

    private val request = PaykitPaymentRequest(
        paymentRequestId = "payment-request",
        counterparty = target.publicKey,
        counterpartyReceiverPath = target.receiverPath,
        amountValue = "0.00025",
        amountSats = draft.amountSats,
        note = draft.note,
        createdAt = Instant.parse("2027-01-15T08:00:00Z"),
        expiresAt = draft.expiresAt,
        acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
    )
}
