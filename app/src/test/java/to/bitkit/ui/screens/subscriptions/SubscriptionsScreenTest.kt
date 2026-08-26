@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import com.synonym.paykit.PaymentRequestLifecycleState
import org.junit.Test
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class SubscriptionsScreenTest {
    private val now = Instant.parse("2027-01-15T08:00:00Z")

    @Test
    fun `next transition includes the next recurring period`() {
        assertEquals(
            Instant.parse("2027-01-22T08:00:00Z"),
            nextSubscriptionTransition(listOf(subscription(PaykitRecurrenceUnit.Week)), now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `next transition includes the next local month`() {
        assertEquals(
            Instant.parse("2027-02-01T00:00:00Z"),
            nextSubscriptionTransition(listOf(subscription(PaykitRecurrenceUnit.Year)), now, ZoneOffset.UTC),
        )
    }

    @Test
    fun `terminal open ended subscription omits timing`() {
        val terminal = subscription(PaykitRecurrenceUnit.Week).copy(
            lifecycleState = PaymentRequestLifecycleState.CANCELED,
        )

        assertFalse(terminal.shouldShowTiming(now))
        assertTrue(subscription(PaykitRecurrenceUnit.Week).shouldShowTiming(now))
    }

    @Test
    fun `only active open ended subscriptions can be canceled`() {
        val openEnded = subscription(PaykitRecurrenceUnit.Week)
        val fixedEnd = openEnded.copy(
            recurrence = openEnded.recurrence.copy(
                endsAt = Instant.parse("2027-01-22T08:00:00Z"),
            ),
        )

        assertTrue(openEnded.canCancel(now))
        assertFalse(fixedEnd.canCancel(now))
    }

    private fun subscription(unit: PaykitRecurrenceUnit) = PaykitSubscription(
        paymentRequestId = "subscription",
        counterparty = "pubkypayee",
        counterpartyReceiverPath = "bitkit/server",
        amountValue = "0.001",
        amountSats = 100_000u,
        note = "Subscription",
        createdAt = Instant.parse("2027-01-01T08:00:00Z"),
        proposalExpiresAt = null,
        recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = unit,
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            anchor = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = null,
        ),
        metadata = PaykitSubscriptionMetadata(description = null, benefits = emptyList()),
        acceptedPaymentEndpointIdentifiers = listOf("btc-lightning-bolt11"),
        lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
        paidPeriods = emptyList(),
    )
}
