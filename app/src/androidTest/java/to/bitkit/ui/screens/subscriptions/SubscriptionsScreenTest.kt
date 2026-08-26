@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.screens.paymentrequests.PaymentRequestsContent
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ComposeUi
class SubscriptionsScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

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
                    paymentsContent = {
                        PaymentRequestsContent(
                            requests = persistentListOf(),
                            pending = persistentListOf(),
                            contacts = persistentListOf(),
                            subscriptions = persistentListOf(),
                            canRequestPayment = true,
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
