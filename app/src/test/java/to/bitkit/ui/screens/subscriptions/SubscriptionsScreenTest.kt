@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import com.synonym.paykit.PaymentRequestLifecycleState
import org.junit.Test
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.MethodId
import to.bitkit.repositories.PaykitBillingPeriod
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import to.bitkit.repositories.isPaidFromSpending
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
    fun `proposed subscription review updates at the first billing boundary`() {
        val proposed = subscription(PaykitRecurrenceUnit.Month).let {
            it.copy(
                lifecycleState = PaymentRequestLifecycleState.PROPOSED,
                recurrence = it.recurrence.copy(startsAt = now, anchor = Instant.parse("2027-01-15T08:01:00Z")),
            )
        }
        val boundary = Instant.parse("2027-01-15T08:01:00Z")
        assertEquals(boundary, proposed.paymentDueOnAcceptance(now)?.billingPeriod?.endsAt)
        assertEquals(boundary, nextSubscriptionTransition(listOf(proposed), now))
        assertEquals(
            Instant.parse("2027-02-15T08:01:00Z"),
            nextSubscriptionTransition(listOf(proposed), boundary),
        )
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
    fun `spending pays only when lightning is accepted and affordable`() {
        val amount = 100_000uL
        val lightningOnly = subscription(PaykitRecurrenceUnit.Month, amountSats = amount)
        val onchainOnly = lightningOnly.copy(
            acceptedPaymentEndpointIdentifiers = listOf(MethodId.P2wpkh.rawValue),
        )
        val both = lightningOnly.copy(
            acceptedPaymentEndpointIdentifiers = listOf(
                MethodId.P2wpkh.rawValue,
                MethodId.Bolt11.rawValue,
            ),
        )

        assertTrue(lightningOnly.acceptsLightningPayment)
        assertFalse(onchainOnly.acceptsLightningPayment)
        // Lightning wins when both are offered, matching payablePreferenceOrder.
        assertTrue(both.acceptsLightningPayment)

        // Enough spending balance, so the payment leaves it.
        assertTrue(lightningOnly.isPaidFromSpending(maxSendLightningSats = amount))
        assertTrue(lightningOnly.isPaidFromSpending(maxSendLightningSats = amount + 1uL))
        // A wallet that cannot cover the amount over lightning falls back to savings.
        assertFalse(lightningOnly.isPaidFromSpending(maxSendLightningSats = amount - 1uL))
        assertFalse(lightningOnly.isPaidFromSpending(maxSendLightningSats = 0uL))
        // Accepting only on-chain never draws on spending, however large the balance.
        assertFalse(onchainOnly.isPaidFromSpending(maxSendLightningSats = amount * 10uL))
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
    fun `terminal subscription with paid periods expires on its last period end`() {
        val lastPeriodEnd = Instant.parse("2027-01-08T08:00:00Z")
        val terminal = subscription(PaykitRecurrenceUnit.Week).copy(
            lifecycleState = PaymentRequestLifecycleState.CANCELED,
            paidPeriods = listOf(
                PaykitBillingPeriod(
                    startsAt = Instant.parse("2027-01-01T08:00:00Z"),
                    endsAt = lastPeriodEnd,
                ),
                PaykitBillingPeriod(
                    startsAt = Instant.parse("2026-12-25T08:00:00Z"),
                    endsAt = Instant.parse("2027-01-01T08:00:00Z"),
                ),
            ),
        )

        assertTrue(terminal.shouldShowTiming(now))
        assertEquals(lastPeriodEnd, terminal.expiryDate())
    }

    @Test
    fun `fixed end date wins over paid periods as the expiry date`() {
        val endsAt = Instant.parse("2027-03-01T08:00:00Z")
        val openEnded = subscription(PaykitRecurrenceUnit.Week)
        val fixedEnd = openEnded.copy(
            recurrence = openEnded.recurrence.copy(endsAt = endsAt),
            paidPeriods = listOf(
                PaykitBillingPeriod(
                    startsAt = Instant.parse("2027-01-01T08:00:00Z"),
                    endsAt = Instant.parse("2027-01-08T08:00:00Z"),
                ),
            ),
        )

        assertEquals(endsAt, fixedEnd.expiryDate())
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
