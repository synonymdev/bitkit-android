@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.repositories.PaykitPaymentRequest
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestDirection
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ComposeUi
class PaymentRequestsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun queueShowsIncomingRequestAndSeeAllAction() {
        val request = request(id = "incoming")

        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestsSheetContent(
                    requests = persistentListOf(request),
                    contacts = persistentListOf(),
                    onNotNow = {},
                    onSeeAll = {},
                    onPay = {},
                    onReject = { Result.success(Unit) },
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestRowincoming").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestsSeeAll").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun historyGroupsCompletedRequestsAndKeepsActiveOutgoingRequests() {
        val accepted = request(id = "accepted").copy(
            createdAt = Clock.System.now(),
            lifecycleState = PaymentRequestLifecycleState.ACCEPTED,
        )
        val outgoing = request(id = "outgoing").copy(
            createdAt = Clock.System.now(),
            direction = PaykitPaymentRequestDirection.Outgoing,
            deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
        )

        composeTestRule.setContent {
            AppThemeSurface {
                PaymentRequestsContent(
                    requests = persistentListOf(outgoing, accepted),
                    pending = persistentListOf(),
                    contacts = persistentListOf(),
                    canRequestPayment = true,
                    onBack = {},
                    onRequestPayment = {},
                    onPay = {},
                    onReject = { Result.success(Unit) },
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestRowaccepted").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestRowoutgoing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Waiting for", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("PAYMENT REQUESTS").assertIsDisplayed()
        composeTestRule.onNodeWithText("THIS MONTH").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestCreate").assertIsDisplayed()
    }

    private fun request(id: String) = PaykitPaymentRequest(
        paymentRequestId = id,
        counterparty = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg",
        counterpartyReceiverPath = "bitkit/wallet",
        amountValue = "0.00025",
        amountSats = 25_000uL,
        note = "Dinner",
        createdAt = Instant.parse("2027-01-15T08:00:00Z"),
        expiresAt = null,
        acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
    )
}
