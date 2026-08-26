@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.PaymentRequestLifecycleState
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class PaykitSubscriptionTest {
    @Test
    fun `monthly recurrence returns to anchor day after a short month`() {
        val recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Month,
            startsAt = Instant.parse("2027-01-31T08:00:00Z"),
            anchor = Instant.parse("2027-01-31T08:00:00Z"),
            endsAt = null,
        )

        val periods = recurrence.periodsThrough(
            date = Instant.parse("2027-03-15T08:00:00Z"),
            acceptedAt = Instant.parse("2027-01-31T08:00:00Z"),
        )

        assertEquals(2, periods.size)
        assertEquals(Instant.parse("2027-02-28T08:00:00Z"), periods[0].endsAt)
        assertEquals(Instant.parse("2027-03-31T08:00:00Z"), periods[1].endsAt)
    }

    @Test
    fun `recurrence uses the first anchor boundary after start`() {
        val recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Month,
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            anchor = Instant.parse("2027-01-15T08:00:00Z"),
            endsAt = null,
        )

        val period = recurrence.periodsThrough(
            date = Instant.parse("2027-01-10T08:00:00Z"),
            acceptedAt = Instant.parse("2027-01-01T08:00:00Z"),
        ).first()

        assertEquals(Instant.parse("2027-01-01T08:00:00Z"), period.startsAt)
        assertEquals(Instant.parse("2027-01-15T08:00:00Z"), period.endsAt)
    }

    @Test
    fun `recurrence returns consecutive upcoming periods`() {
        val recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Week,
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            anchor = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = null,
        )

        val periods = recurrence.upcomingPeriodsAfter(
            date = Instant.parse("2027-01-02T08:00:00Z"),
            limit = 3,
        )

        assertEquals(
            listOf(
                Instant.parse("2027-01-08T08:00:00Z"),
                Instant.parse("2027-01-15T08:00:00Z"),
                Instant.parse("2027-01-22T08:00:00Z"),
            ),
            periods.map { it.startsAt },
        )
    }

    @Test
    fun `recurrence preserves nanosecond billing boundaries`() {
        val recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Day,
            startsAt = Instant.parse("2027-01-01T08:00:00.123100Z"),
            anchor = Instant.parse("2027-01-01T08:00:00.123900Z"),
            endsAt = null,
        )

        val period = recurrence.periodsThrough(
            date = Instant.parse("2027-01-01T08:00:01Z"),
            acceptedAt = Instant.parse("2027-01-01T08:00:00Z"),
        ).first()

        assertEquals("2027-01-01T08:00:00.123100Z", period.sdkValue.startsAt)
        assertEquals("2027-01-01T08:00:00.123900Z", period.sdkValue.endsAt)
    }

    @Test
    fun `recurrence does not invent period when anchor search exceeds limit`() {
        val recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Day,
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            anchor = Instant.parse("2077-01-01T08:00:00Z"),
            endsAt = null,
        )

        val periods = recurrence.periodsThrough(
            date = Instant.parse("2027-01-01T08:00:00Z"),
            acceptedAt = Instant.parse("2027-01-01T08:00:00Z"),
        )

        assertTrue(periods.isEmpty())
        assertFalse(recurrence.canMaterializePeriods)
    }

    @Test
    fun `day week month and year are supported but minute and hour are not`() {
        assertTrue(PaykitRecurrenceUnit.Day.isSupported)
        assertTrue(PaykitRecurrenceUnit.Week.isSupported)
        assertTrue(PaykitRecurrenceUnit.Month.isSupported)
        assertTrue(PaykitRecurrenceUnit.Year.isSupported)
        assertFalse(PaykitRecurrenceUnit.Minute.isSupported)
        assertFalse(PaykitRecurrenceUnit.Hour.isSupported)
    }

    @Test
    fun `subscription payment matching includes counterparty and receiver path`() {
        val recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Month,
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            anchor = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = null,
        )
        val subscription = PaykitSubscription(
            paymentRequestId = "shared",
            counterparty = "counterparty-a",
            counterpartyReceiverPath = "bitkit/server",
            amountValue = "0.001",
            amountSats = 100_000uL,
            note = null,
            createdAt = null,
            proposalExpiresAt = null,
            recurrence = recurrence,
            metadata = PaykitSubscriptionMetadata(null, emptyList()),
            acceptedPaymentEndpointIdentifiers = listOf("bitcoin-lightning-bolt11"),
            lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
            paidPeriods = emptyList(),
        )
        val request = subscription.requestsThrough(
            date = Instant.parse("2027-01-15T08:00:00Z"),
            acceptedAt = Instant.parse("2027-01-01T08:00:00Z"),
        ).single()

        assertTrue(request.belongsTo(subscription))
        assertFalse(request.copy(counterparty = "counterparty-b").belongsTo(subscription))
        assertFalse(request.copy(counterpartyReceiverPath = "bitkit/wallet").belongsTo(subscription))
    }
}
