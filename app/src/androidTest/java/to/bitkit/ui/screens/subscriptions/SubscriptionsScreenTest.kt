@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.models.PaykitRequestPricing
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.screens.paymentrequests.PaymentRequestsContent
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ComposeUi
class SubscriptionsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun subscriptionReviewDisclosesAcceptedMethods() {
        val now = Instant.parse("2027-01-15T08:00:00Z")
        val subscription = mutableStateOf(
            PaykitSubscription(
                paymentRequestId = "subscription",
                counterparty = "pubky" + "y".repeat(52),
                amount = PaykitAmount(PaykitAsset.USD, 300uL),
                paymentReference = "invoice",
                pricing = PaykitRequestPricing(),
                note = null,
                createdAt = now,
                proposalExpiresAt = Instant.parse("2027-01-22T08:00:00Z"),
                recurrence = PaykitSubscriptionRecurrence(1, PaykitRecurrenceUnit.Month, now, now, null),
                metadata = PaykitSubscriptionMetadata(description = null, benefits = emptyList()),
                acceptedPaymentEndpointIdentifiers = listOf(MethodId.UsdtArbitrum.rawValue),
                lifecycleState = PaymentRequestLifecycleState.PROPOSED,
                paidPeriods = emptyList(),
            )
        )
        composeTestRule.setContent {
            AppThemeSurface {
                SubscriptionReview(
                    subscription = subscription.value,
                    contact = PubkyProfile.forDisplay(subscription.value.counterparty, "Anna", null),
                    now = now,
                    onDetails = {},
                    onSubscribe = { true },
                )
            }
        }
        composeTestRule.onNodeWithTag("SubscriptionAcceptedMethods").assertIsDisplayed()
        composeTestRule.onNodeWithText("USDT").assertIsDisplayed()
        composeTestRule.onNodeWithText("Swipe To Subscribe").assertIsDisplayed()
        val pixels = composeTestRule.onNodeWithTag("GRAB").captureToImage().toPixelMap()
        assertTrue((0 until pixels.height).any { y ->
            (0 until pixels.width).any { x -> pixels[x, y].toArgb() == Colors.Usdt.toArgb() }
        }, "USDT subscription swipe must use the USDT accent")
        composeTestRule.runOnIdle {
            subscription.value = subscription.value.copy(
                acceptedPaymentEndpointIdentifiers = listOf(MethodId.P2tr.rawValue, MethodId.Lnurl.rawValue),
            )
        }
        composeTestRule.onNodeWithText("USDT").assertDoesNotExist()
        composeTestRule.onNodeWithText("Savings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Spending").assertIsDisplayed()
    }

    @Test
    fun emptyOverviewOffersCreationWithoutDiscover() {
        var requestedCreation = false
        composeTestRule.setContent {
            AppThemeSurface {
                SubscriptionsContent(
                    subscriptions = persistentListOf(),
                    contacts = persistentListOf(),
                    acceptedAt = { null },
                    now = Instant.parse("2027-01-15T08:00:00Z"),
                    onBack = {},
                    initialTab = SubscriptionTab.Overview,
                    pendingPaymentRequestCount = 0,
                    onSubscription = {},
                    onCreateSubscription = { requestedCreation = true },
                    paymentsContent = {},
                )
            }
        }

        composeTestRule.onNodeWithText("At the moment, you don’t have any active subscriptions from any providers.")
            .assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Discover").assertCountEquals(0)
        composeTestRule.onNodeWithTag("SubscriptionCreate").assertIsDisplayed().performClick()
        assertTrue(requestedCreation)
    }

    @Test
    fun paymentsTabShowsEligibleOneTimeRequestAction() {
        var requestedPayment = false
        composeTestRule.setContent {
            AppThemeSurface {
                SubscriptionsContent(
                    subscriptions = persistentListOf(),
                    contacts = persistentListOf(),
                    acceptedAt = { null },
                    now = Instant.parse("2027-01-15T08:00:00Z"),
                    onBack = {},
                    initialTab = SubscriptionTab.Payments,
                    pendingPaymentRequestCount = 0,
                    onSubscription = {},
                    onCreateSubscription = {},
                    paymentsContent = {
                        PaymentRequestsContent(
                            requests = persistentListOf(),
                            pending = persistentListOf(),
                            contacts = persistentListOf(),
                            subscriptions = persistentListOf(),
                            dismissingRequestIds = persistentSetOf(),
                            onBack = {},
                            onRequestPayment = { requestedPayment = true },
                            onPay = {},
                            onDismiss = { Result.success(Unit) },
                            onDetails = {},
                            showsNavigationBar = false,
                        )
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("PaymentRequestCreate").assertIsDisplayed().performClick()
        assertTrue(requestedPayment)
    }
}
