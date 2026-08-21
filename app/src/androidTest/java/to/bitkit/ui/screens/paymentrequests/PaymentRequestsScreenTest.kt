@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.paymentrequests

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
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
            PaymentRequestsTestSurface {
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
        composeTestRule.onNodeWithTag("MoneyPrimary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("MoneySecondary").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestsSeeAll").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").assertIsDisplayed()
    }

    @Test
    fun historyGroupsCompletedRequestsAndKeepsActiveOutgoingRequests() {
        val now = Clock.System.now()
        val accepted = request(id = "accepted").copy(
            createdAt = now,
            lifecycleState = PaymentRequestLifecycleState.ACCEPTED,
        )
        val outgoing = request(id = "outgoing").copy(
            createdAt = now,
            direction = PaykitPaymentRequestDirection.Outgoing,
            deliveryStatus = PaykitPaymentRequestDeliveryStatus.Sent,
        )

        composeTestRule.setContent {
            PaymentRequestsTestSurface {
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
        composeTestRule.onNodeWithText("TODAY").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestCreate").assertIsDisplayed()
    }

    @Test
    fun emptyHistoryMatchesPaymentRequestsEmptyState() {
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                PaymentRequestsContent(
                    requests = persistentListOf(),
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

        composeTestRule.onNodeWithText("NO PAYMENT\nHISTORY").assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestsEmptyIllustration").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "You have not made any payments to providers and don’t have any payment requests yet."
        ).assertIsDisplayed()
        composeTestRule.onNodeWithTag("PaymentRequestCreate").assertIsDisplayed()
    }

    @Test
    fun requestPaymentIsHiddenWithoutEligibleRecipients() {
        composeTestRule.setContent {
            PaymentRequestsTestSurface {
                PaymentRequestsContent(
                    requests = persistentListOf(),
                    pending = persistentListOf(),
                    contacts = persistentListOf(),
                    canRequestPayment = false,
                    onBack = {},
                    onRequestPayment = {},
                    onPay = {},
                    onReject = { Result.success(Unit) },
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestCreate").assertDoesNotExist()
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

@Composable
private fun PaymentRequestsTestSurface(content: @Composable () -> Unit) {
    AppThemeSurface {
        CompositionLocalProvider(LocalInspectionMode provides true) {
            content()
        }
    }
}
