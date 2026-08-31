@file:OptIn(kotlin.time.ExperimentalTime::class)

package to.bitkit.repositories

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import com.synonym.paykit.PaymentRequestLifecycleState
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.ui.EXTRA_PAYKIT_BILLING_PERIOD_STARTS_AT
import to.bitkit.ui.EXTRA_PAYKIT_COUNTERPARTY
import to.bitkit.ui.EXTRA_PAYKIT_COUNTERPARTY_RECEIVER_PATH
import to.bitkit.ui.EXTRA_PAYKIT_PAYER_IDENTITY
import to.bitkit.ui.EXTRA_PAYKIT_PAYMENT_REQUEST_ID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

@Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class PaykitSubscriptionNotificationSchedulerTest {
    private companion object {
        const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        const val PAYER_IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        const val PAYMENT_REQUEST_ID = "request-id"
        const val RECEIVER_PATH = "bitkit/server"
        const val WORK_TAG = "paykit-subscriptions"
        val NOW = Instant.parse("2027-01-02T08:00:00Z")
        val NEXT_PERIOD_START = Instant.parse("2027-01-08T08:00:00Z")
        val WORK_NAME = "paykit-subscription-$PAYER_IDENTITY|$COUNTERPARTY|$RECEIVER_PATH|" +
            "$PAYMENT_REQUEST_ID|$NEXT_PERIOD_START"
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workClient = mock<PaykitSubscriptionWorkClient>()
    private val clock = object : Clock {
        override fun now(): Instant = NOW
    }
    private lateinit var sut: PaykitSubscriptionNotificationScheduler

    @Before
    fun setUp() {
        clearPreferences()
        sut = PaykitSubscriptionNotificationScheduler(context, clock, workClient)
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun `synchronize schedules upcoming period with request identity`() {
        sut.synchronize(
            subscriptions = listOf(subscription()),
            acceptedAt = { NOW },
            pendingRequestIds = emptySet(),
            payerIdentity = PAYER_IDENTITY,
            notificationsEnabled = true,
        )

        val requestCaptor = argumentCaptor<OneTimeWorkRequest>()
        verify(workClient).enqueueUniqueWork(eq(WORK_NAME), eq(ExistingWorkPolicy.KEEP), requestCaptor.capture())
        val request = requestCaptor.firstValue

        assertEquals((NEXT_PERIOD_START - NOW).inWholeMilliseconds, request.workSpec.initialDelay)
        assertTrue(WORK_TAG in request.tags)
        assertEquals(PAYMENT_REQUEST_ID, request.workSpec.input.getString(EXTRA_PAYKIT_PAYMENT_REQUEST_ID))
        assertEquals(PAYER_IDENTITY, request.workSpec.input.getString(EXTRA_PAYKIT_PAYER_IDENTITY))
        assertEquals(COUNTERPARTY, request.workSpec.input.getString(EXTRA_PAYKIT_COUNTERPARTY))
        assertEquals(RECEIVER_PATH, request.workSpec.input.getString(EXTRA_PAYKIT_COUNTERPARTY_RECEIVER_PATH))
        assertEquals(
            NEXT_PERIOD_START.toString(),
            request.workSpec.input.getString(EXTRA_PAYKIT_BILLING_PERIOD_STARTS_AT),
        )
    }

    @Test
    fun `synchronize cancels work no longer required`() {
        sut.synchronize(
            subscriptions = listOf(subscription()),
            acceptedAt = { NOW },
            pendingRequestIds = emptySet(),
            payerIdentity = PAYER_IDENTITY,
            notificationsEnabled = true,
        )
        clearInvocations(workClient)

        sut.synchronize(
            subscriptions = emptyList(),
            acceptedAt = { null },
            pendingRequestIds = emptySet(),
            payerIdentity = PAYER_IDENTITY,
            notificationsEnabled = true,
        )

        verify(workClient).cancelUniqueWork(WORK_NAME)
    }

    @Test
    fun `disabling and canceling clear tagged work`() {
        sut.synchronize(
            subscriptions = emptyList(),
            acceptedAt = { null },
            pendingRequestIds = emptySet(),
            payerIdentity = PAYER_IDENTITY,
            notificationsEnabled = false,
        )
        verify(workClient).cancelAllWorkByTag(WORK_TAG)
        clearInvocations(workClient)

        sut.cancel()

        verify(workClient).cancelAllWorkByTag(WORK_TAG)
    }

    private fun clearPreferences() {
        context.getSharedPreferences("paykit-subscription-notifications", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private fun subscription() = PaykitSubscription(
        paymentRequestId = PAYMENT_REQUEST_ID,
        counterparty = COUNTERPARTY,
        counterpartyReceiverPath = RECEIVER_PATH,
        amountValue = "0.00025",
        amountSats = 25_000uL,
        note = "Weekly coffee",
        createdAt = NOW,
        proposalExpiresAt = null,
        recurrence = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Week,
            startsAt = Instant.parse("2027-01-01T08:00:00Z"),
            anchor = Instant.parse("2027-01-01T08:00:00Z"),
            endsAt = null,
        ),
        metadata = PaykitSubscriptionMetadata(description = null, benefits = emptyList()),
        acceptedPaymentEndpointIdentifiers = listOf(MethodId.Bolt11.rawValue),
        lifecycleState = PaymentRequestLifecycleState.ACTIVE_RECURRING,
        paidPeriods = emptyList(),
    )
}
