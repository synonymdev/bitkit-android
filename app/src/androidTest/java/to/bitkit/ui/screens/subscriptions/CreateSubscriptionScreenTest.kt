@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import com.synonym.paykit.PaymentRequestLifecycleState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PaykitPaymentRequestDeliveryStatus
import to.bitkit.repositories.PaykitPaymentRequestTarget
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import to.bitkit.repositories.PaykitSubscriptionRole
import to.bitkit.test.annotations.ComposeUi
import to.bitkit.ui.screens.paymentrequests.PaymentRequestExpiration
import to.bitkit.ui.theme.AppThemeSurface
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@ComposeUi
class CreateSubscriptionScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun detailsRequiresAmountNameAndCompletedIconLoad() {
        var amount by mutableStateOf(0uL)
        var name by mutableStateOf("")
        var frequency by mutableStateOf(PaykitRecurrenceUnit.Month)
        var isLoadingIcon by mutableStateOf(false)
        var choseRecipient = false
        composeTestRule.setContent {
            AppThemeSurface {
                CreateSubscriptionDetails(
                    amountSats = amount,
                    name = name,
                    description = "Monthly support",
                    frequency = frequency,
                    selectedIconUri = null,
                    isLoadingIcon = isLoadingIcon,
                    onAmountClick = {},
                    onNameChange = { name = it },
                    onDescriptionChange = {},
                    onFrequencyChange = { frequency = it },
                    onIconSelected = {},
                    onChooseRecipient = { choseRecipient = true },
                )
            }
        }

        composeTestRule.onNodeWithTag("SubscriptionChooseRecipient").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("SubscriptionName").performTextInput("Support")
        composeTestRule.onNodeWithTag("SubscriptionChooseRecipient").assertIsNotEnabled()
        composeTestRule.runOnIdle { amount = 1000uL }
        composeTestRule.onNodeWithTag("SubscriptionChooseRecipient").assertIsEnabled()
        listOf(PaykitRecurrenceUnit.Day, PaykitRecurrenceUnit.Week, PaykitRecurrenceUnit.Month, PaykitRecurrenceUnit.Year)
            .forEach { option ->
                composeTestRule.onNodeWithTag("Tab-${option.name.lowercase()}").performClick()
                assertEquals(option, frequency)
            }
        composeTestRule.runOnIdle { isLoadingIcon = true }
        composeTestRule.onNodeWithTag("SubscriptionChooseRecipient").assertIsNotEnabled()
        composeTestRule.runOnIdle { isLoadingIcon = false }
        composeTestRule.onNodeWithTag("SubscriptionChooseRecipient").performClick()
        assertTrue(choseRecipient)
    }

    @Test
    fun recipientAllowsExactlyOneSelectionAndChangesExpiry() {
        val second = PaykitPaymentRequestTarget("pubky" + "z".repeat(52), "bitkit/wallet")
        var selected by mutableStateOf<PaykitPaymentRequestTarget?>(null)
        var expiration by mutableStateOf(PaymentRequestExpiration.Week)
        var proposedTo: PaykitPaymentRequestTarget? = null
        composeTestRule.setContent {
            AppThemeSurface {
                SubscriptionRecipient(
                    targets = persistentListOf(target, second),
                    contacts = persistentListOf(contact, PubkyProfile.forDisplay(second.publicKey, "Bob", null)),
                    selectedTarget = selected,
                    expiration = expiration,
                    isCreating = false,
                    isLoadingIcon = false,
                    onBack = {},
                    onPaste = { "" },
                    onSelected = { selected = it },
                    onExpirationChange = { expiration = it },
                    onPropose = { proposedTo = selected },
                )
            }
        }

        composeTestRule.onNodeWithTag("SubscriptionPropose").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("SubscriptionContact${target.publicKey}").performClick().assertIsSelected()
        composeTestRule.onNodeWithTag("SubscriptionContact${second.publicKey}").performClick().assertIsSelected()
        composeTestRule.onNodeWithTag("SubscriptionContact${target.publicKey}").assertIsNotSelected()
        composeTestRule.onNodeWithTag("SubscriptionExpiration").performClick()
        composeTestRule.onNodeWithText("1 hour").performClick()
        assertEquals(PaymentRequestExpiration.Hour, expiration)
        composeTestRule.onNodeWithTag("SubscriptionPropose").performClick()
        assertEquals(second, proposedTo)
    }

    @Test
    fun confirmationNeverClaimsQueuedProposalWasSent() {
        var delivery by mutableStateOf(PaykitPaymentRequestDeliveryStatus.Queued)
        composeTestRule.setContent {
            AppThemeSurface {
                Box(Modifier.height(520.dp)) {
                    SubscriptionProposalSent(
                        subscription.copy(deliveryStatus = delivery, note = "Long subscription name ".repeat(7)),
                        contact,
                        onDone = {},
                    )
                }
            }
        }

        composeTestRule.onNodeWithText("Queued").assertIsDisplayed()
        composeTestRule.onNodeWithText("OK").assertIsDisplayed()
        composeTestRule.onNodeWithTag("SubscriptionConfirmationBody").performScrollToNode(hasText("Anna"))
        composeTestRule.onNodeWithText("Anna").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Sent", substring = true, ignoreCase = true).assertCountEquals(0)
        composeTestRule.runOnIdle { delivery = PaykitPaymentRequestDeliveryStatus.Sent }
        composeTestRule.onNodeWithText("Sent").assertIsDisplayed()
        composeTestRule.onNodeWithTag("SubscriptionConfirmationBody")
            .performScrollToNode(hasText("You have sent a subscription proposal to"))
        composeTestRule.onNodeWithText("You have sent a subscription proposal to").assertIsDisplayed()
        composeTestRule.onNodeWithTag("SubscriptionConfirmationBody")
            .performScrollToNode(hasText("Monthly subscription"))
        composeTestRule.onNodeWithText("Monthly subscription").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Queued", substring = true, ignoreCase = true).assertCountEquals(0)
        composeTestRule.onNodeWithText("OK").assertIsDisplayed()
    }

    private val target = PaykitPaymentRequestTarget("pubky" + "y".repeat(52), "bitkit/wallet")
    private val contact = PubkyProfile.forDisplay(target.publicKey, "Anna", null)
    private val startsAt = Instant.parse("2027-01-15T08:00:00Z")
    private val subscription = PaykitSubscription(
        paymentRequestId = "creator-proposal",
        counterparty = target.publicKey,
        counterpartyReceiverPath = target.receiverPath,
        amountValue = "0.00001",
        amountSats = 1000uL,
        note = "Support",
        createdAt = startsAt,
        proposalExpiresAt = Instant.parse("2027-01-22T08:00:00Z"),
        recurrence = PaykitSubscriptionRecurrence(1, PaykitRecurrenceUnit.Month, startsAt, startsAt, null),
        metadata = PaykitSubscriptionMetadata(description = null, benefits = emptyList()),
        acceptedPaymentEndpointIdentifiers = emptyList(),
        role = PaykitSubscriptionRole.Payee,
        lifecycleState = PaymentRequestLifecycleState.PROPOSED,
        paidPeriods = emptyList(),
    )
}
