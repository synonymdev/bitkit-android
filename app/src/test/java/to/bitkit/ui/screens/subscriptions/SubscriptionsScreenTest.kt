@file:OptIn(ExperimentalTime::class)

package to.bitkit.ui.screens.subscriptions

import com.synonym.paykit.PaymentRequestLifecycleState
import org.junit.Test
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.PaykitBillingPeriod
import to.bitkit.repositories.PaykitRecurrenceUnit
import to.bitkit.repositories.PaykitSubscription
import to.bitkit.repositories.PaykitSubscriptionId
import to.bitkit.repositories.PaykitSubscriptionMetadata
import to.bitkit.repositories.PaykitSubscriptionRecurrence
import to.bitkit.repositories.PaykitSubscriptionRole
import to.bitkit.repositories.runsUntilPaidThrough
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class SubscriptionsScreenTest {
    private val now = Instant.parse("2027-01-15T08:00:00Z")
    private val paidThrough = Instant.parse("2027-02-01T08:00:00Z")

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
    fun `proposed subscription review names the period accepting pays under a clock offset`() {
        val proposed = subscription(PaykitRecurrenceUnit.Month).let {
            it.copy(
                lifecycleState = PaymentRequestLifecycleState.PROPOSED,
                recurrence = it.recurrence.copy(startsAt = now, anchor = Instant.parse("2027-01-15T08:01:00Z")),
            )
        }
        val shiftedNow = Instant.parse("2027-02-15T08:00:00Z")

        assertEquals(
            Instant.parse("2027-01-15T08:01:00Z"),
            proposed.paymentDueOnAcceptance(shiftedNow, acceptedAt = now)?.billingPeriod?.endsAt,
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
    fun `canceled subscription before its paid period ends reads active and expires`() {
        val canceled = canceledWithPaidThrough()

        assertTrue(canceled.runsUntilPaidThrough(now))
        assertEquals(R.string.subscriptions__active, canceled.statusRes(now))
        assertEquals(R.string.subscriptions__expires, canceled.timingTitleRes(now))
        assertEquals(R.string.subscriptions__expires_date to paidThrough, canceled.rowSubtitleSpec(now))
        assertTrue(canceled.shouldShowTiming(now))
        assertEquals(paidThrough, canceled.expiryDate())
        assertFalse(canceled.canCancel(now))
    }

    @Test
    fun `canceled subscription expires when its paid period ends`() {
        val canceled = canceledWithPaidThrough()

        assertFalse(canceled.runsUntilPaidThrough(paidThrough))
        assertEquals(R.string.subscriptions__expired, canceled.statusRes(paidThrough))
        assertEquals(R.string.subscriptions__expired, canceled.timingTitleRes(paidThrough))
        assertEquals(R.string.subscriptions__expired to null, canceled.rowSubtitleSpec(paidThrough))
        assertTrue(canceled.hasEnded(paidThrough))
        assertFalse(canceled.hasEnded(now))
    }

    @Test
    fun `canceled subscription stops running at its last paid period even with a later end date`() {
        val canceled = canceledWithPaidThrough().let {
            it.copy(recurrence = it.recurrence.copy(endsAt = Instant.parse("2027-06-01T08:00:00Z")))
        }

        assertEquals(paidThrough, canceled.expiryDate())
        assertTrue(canceled.runsUntilPaidThrough(now))
        assertFalse(canceled.runsUntilPaidThrough(paidThrough))
        assertEquals(R.string.subscriptions__expired, canceled.statusRes(paidThrough))
        assertEquals(R.string.subscriptions__expires_date to paidThrough, canceled.rowSubtitleSpec(now))
        assertEquals(R.string.subscriptions__expires_date to paidThrough, canceled.rowSubtitleSpec(paidThrough))
        assertEquals(paidThrough, nextSubscriptionTransition(listOf(canceled), now))
    }

    @Test
    fun `canceled subscription the user created stays under created until its paid period ends`() {
        val created = canceledWithPaidThrough().copy(role = PaykitSubscriptionRole.Payee)
        val sections = subscriptionSections(listOf(created), { now }, now)

        assertTrue(created.runsUntilPaidThrough(now))
        assertFalse(created.hasEnded(now))
        assertEquals(R.string.subscriptions__active, created.statusRes(now))
        assertEquals(R.string.subscriptions__expires, created.timingTitleRes(now))
        assertEquals(R.string.subscriptions__expires_date to paidThrough, created.rowSubtitleSpec(now))
        assertEquals(listOf(created), sections.created)
        assertEquals(emptyList(), sections.active)
        assertEquals(emptyList(), sections.expired)
        assertEquals(paidThrough, nextSubscriptionTransition(listOf(created), now))
        assertEquals(0L, subscriptionMonthlyCostSats(listOf(created), now))
    }

    @Test
    fun `canceled subscription the user created moves to expired when its paid period ends`() {
        val created = canceledWithPaidThrough().copy(role = PaykitSubscriptionRole.Payee)
        val sections = subscriptionSections(listOf(created), { null }, paidThrough)

        assertFalse(created.runsUntilPaidThrough(paidThrough))
        assertTrue(created.hasEnded(paidThrough))
        assertEquals(R.string.subscriptions__expired, created.statusRes(paidThrough))
        assertEquals(R.string.subscriptions__expired, created.timingTitleRes(paidThrough))
        assertEquals(R.string.subscriptions__expired to null, created.rowSubtitleSpec(paidThrough))
        assertEquals(listOf(created), sections.expired)
        assertEquals(emptyList(), sections.created)
        assertEquals(emptyList(), sections.active)
    }

    @Test
    fun `created subscription that was never canceled stays under created after its end date`() {
        val ended = canceledWithPaidThrough().copy(
            role = PaykitSubscriptionRole.Payee,
            lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
        ).let { it.copy(recurrence = it.recurrence.copy(endsAt = paidThrough)) }
        val sections = subscriptionSections(listOf(ended), { null }, paidThrough)

        assertEquals(listOf(ended), sections.created)
        assertEquals(emptyList(), sections.expired)
        assertTrue(ended.hasEnded(paidThrough))
    }

    @Test
    fun `canceled subscription without an end date shows no timing`() {
        val canceled = subscription(PaykitRecurrenceUnit.Month)
            .copy(lifecycleState = PaymentRequestLifecycleState.CANCELED)

        assertFalse(canceled.runsUntilPaidThrough(now))
        assertFalse(canceled.shouldShowTiming(now))
        assertEquals(R.string.subscriptions__expired, canceled.statusRes(now))
    }

    @Test
    fun `active and ended subscriptions keep their status and timing`() {
        val active = subscription(PaykitRecurrenceUnit.Month)
        val fixedEnd = active.copy(recurrence = active.recurrence.copy(endsAt = Instant.parse("2027-06-01T08:00:00Z")))
        val ended = active.copy(recurrence = active.recurrence.copy(endsAt = Instant.parse("2027-01-10T08:00:00Z")))

        assertEquals(R.string.subscriptions__active, active.statusRes(now))
        assertEquals(R.string.subscriptions__renews, active.timingTitleRes(now))
        assertEquals(
            R.string.subscriptions__renews_date to Instant.parse("2027-02-01T08:00:00Z"),
            active.rowSubtitleSpec(now),
        )
        assertEquals(R.string.subscriptions__expires, fixedEnd.timingTitleRes(now))
        assertEquals(R.string.subscriptions__expired, ended.statusRes(now))
        assertEquals(R.string.subscriptions__expired, ended.timingTitleRes(now))
    }

    @Test
    fun `expired proposal with a future end date does not run until paid through`() {
        val expiredProposal = subscription(PaykitRecurrenceUnit.Month).let {
            it.copy(
                lifecycleState = PaymentRequestLifecycleState.PROPOSAL_EXPIRED,
                recurrence = it.recurrence.copy(endsAt = Instant.parse("2027-06-01T08:00:00Z")),
            )
        }

        assertFalse(expiredProposal.runsUntilPaidThrough(now))
        assertEquals(R.string.subscriptions__expired, expiredProposal.timingTitleRes(now))
    }

    @Test
    fun `canceled subscription is listed as active until its paid period ends then as expired`() {
        val canceled = canceledWithPaidThrough()
        val active = subscription(PaykitRecurrenceUnit.Month).copy(paymentRequestId = "active")
        val subscriptions = listOf(canceled, active)
        val accepted = { _: PaykitSubscriptionId -> now }

        val before = subscriptionSections(subscriptions, accepted, now)
        assertEquals(listOf(canceled, active), before.active)
        assertEquals(emptyList(), before.expired)

        val after = subscriptionSections(subscriptions, accepted, paidThrough)
        assertEquals(listOf(active), after.active)
        assertEquals(listOf(canceled), after.expired)
    }

    @Test
    fun `monthly cost counts a canceled subscription until its paid period ends`() {
        val canceled = canceledWithPaidThrough().copy(amountSats = 1_200u)

        assertEquals(1_200L, subscriptionMonthlyCostSats(listOf(canceled), now))
        assertEquals(0L, subscriptionMonthlyCostSats(listOf(canceled), paidThrough))
    }

    @Test
    fun `next transition includes the end of a canceled subscription's paid period`() {
        val canceled = canceledWithPaidThrough()

        assertEquals(paidThrough, nextSubscriptionTransition(listOf(canceled), now))
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

    private fun canceledWithPaidThrough() = subscription(PaykitRecurrenceUnit.Month).copy(
        lifecycleState = PaymentRequestLifecycleState.CANCELED,
        paidPeriods = listOf(
            PaykitBillingPeriod(startsAt = Instant.parse("2027-01-01T08:00:00Z"), endsAt = paidThrough),
        ),
    )

    private fun subscription(
        unit: PaykitRecurrenceUnit,
        every: Int = 1,
        amountSats: ULong = 100_000u,
    ) = PaykitSubscription(
        paymentRequestId = "subscription",
        counterparty = "pubkypayee",
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
