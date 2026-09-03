@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import com.synonym.paykit.PaymentRequestLifecycleState
import org.junit.Test
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.PaykitBillingPeriod
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class SubscriptionsScreenTest {
    private val now = Instant.parse("2027-01-15T08:00:00Z")

    @Test
    fun `next transition includes the next recurring period`() {
        assertEquals(
            Instant.parse("2027-01-22T08:00:00Z"),
            nextSubscriptionTransition(listOf(subscription(PaykitRecurrenceUnit.Week)), now),
        )
    }

    @Test
    fun `next transition uses the next recurring period`() {
        assertEquals(
            Instant.parse("2028-01-01T08:00:00Z"),
            nextSubscriptionTransition(listOf(subscription(PaykitRecurrenceUnit.Year)), now),
        )
    }

    @Test
    fun `monthly cost normalizes recurrence frequencies`() {
        val cases = listOf(
            Triple(PaykitRecurrenceUnit.Day, 1, 36_500L),
            Triple(PaykitRecurrenceUnit.Week, 1, 5_200L),
            Triple(PaykitRecurrenceUnit.Month, 1, 1_200L),
            Triple(PaykitRecurrenceUnit.Month, 2, 600L),
            Triple(PaykitRecurrenceUnit.Year, 1, 100L),
        )

        cases.forEach { (unit, every, expectedSats) ->
            assertEquals(
                expectedSats,
                subscriptionMonthlyCostSats(listOf(subscription(unit, every, 1_200u)), now),
                "Unexpected monthly cost for every '$every' '$unit'",
            )
        }
        val lowCostYearlySubscriptions = List(3) { index ->
            subscription(PaykitRecurrenceUnit.Year, amountSats = 10u).copy(paymentRequestId = "low-cost-$index")
        }
        assertEquals(3L, subscriptionMonthlyCostSats(lowCostYearlySubscriptions, now))
    }

    @Test
    fun `monthly cost includes paid active subscriptions only`() {
        val paidPeriod = PaykitBillingPeriod(
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = Instant.parse("2027-02-01T08:00:00Z"),
        )
        val paidActive = subscription(PaykitRecurrenceUnit.Month, amountSats = 1_200u)
            .copy(paidPeriods = listOf(paidPeriod))
        val canceled = subscription(PaykitRecurrenceUnit.Month, amountSats = 1_200u)
            .copy(lifecycleState = PaymentRequestLifecycleState.CANCELED)
        val proposed = subscription(PaykitRecurrenceUnit.Month, amountSats = 1_200u)
            .copy(lifecycleState = PaymentRequestLifecycleState.PROPOSED)

        assertEquals(
            1_200L,
            subscriptionMonthlyCostSats(listOf(paidActive, canceled, proposed), now),
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

    @Test
    fun `subscription payment confetti follows the settled rail`() {
        assertEquals(
            R.raw.confetti_purple,
            subscriptionConfettiResource(NewTransactionSheetType.LIGHTNING),
        )
        assertEquals(
            R.raw.confetti_orange,
            subscriptionConfettiResource(NewTransactionSheetType.ONCHAIN),
        )
        assertEquals(R.raw.confetti_purple, subscriptionConfettiResource(null))
    }

    private fun subscription(
        unit: PaykitRecurrenceUnit,
        every: Int = 1,
        amountSats: ULong = 100_000u,
    ) = PaykitSubscription(
        paymentRequestId = "subscription",
        counterparty = "pubkypayee",
        counterpartyReceiverPath = "bitkit/server",
        amountValue = "0.001",
        amountSats = amountSats,
        note = "Subscription",
        createdAt = Instant.parse("2027-01-01T08:00:00Z"),
        proposalExpiresAt = null,
        recurrence = PaykitSubscriptionRecurrence(
            every = every,
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
