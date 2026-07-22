@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalTime::class)

package to.bitkit.repositories

import com.synonym.paykit.PaymentReference
import com.synonym.paykit.PaymentRequestAmount
import com.synonym.paykit.PaymentRequestLifecycleState
import com.synonym.paykit.PaymentRequestLocalRole
import com.synonym.paykit.PaymentRequestRecord
import com.synonym.paykit.PaymentRequestTerms
import com.synonym.paykit.PrivateJsonObject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.services.PaykitReceiverPaths
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitPaymentRequestRepoTest : BaseUnitTest(StandardTestDispatcher()) {
    companion object {
        private const val PAYMENT_REQUEST_ID = "550e8400-e29b-41d4-a716-446655440000"
        private const val COUNTERPARTY = "pubkypayee"
        private val START_TIME = Instant.parse("2027-01-15T08:00:00Z")
        private val PAYMENT_REFERENCE = mock<PaymentReference> {
            on { exportText() } doReturn "invoice-123"
        }
        private val METADATA = mock<PrivateJsonObject> {
            on { exportText() } doReturn """{"order":"123"}"""
        }
    }

    private val paykitSdkService = mock<PaykitSdkService>()
    private var schedulerOriginMillis = 0L
    private val clock = object : Clock {
        override fun now(): Instant = START_TIME.plus(
            (testDispatcher.scheduler.currentTime - schedulerOriginMillis).milliseconds,
        )
    }
    private lateinit var sut: PaykitPaymentRequestRepo

    @Before
    fun setUp() = test {
        schedulerOriginMillis = testDispatcher.scheduler.currentTime
        whenever(paykitSdkService.processPendingPrivateMessages()).thenReturn(emptyList())
        whenever(paykitSdkService.receivePrivateMessagesFromLinkedPeers()).thenReturn(emptyList())
        whenever(paykitSdkService.actionableReceivedPaymentRequests()).thenReturn(emptyList())
        sut = PaykitPaymentRequestRepo(testDispatcher, paykitSdkService, clock)
    }

    @After
    fun tearDown() = test {
        sut.clear()
    }

    @Test
    fun `refresh maps actionable bitcoin request`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(60.seconds).toString())
        whenever(paykitSdkService.actionableReceivedPaymentRequests()).thenReturn(listOf(record))

        sut.refresh().getOrThrow()

        val request = sut.pendingRequests.value.single()
        assertEquals(100_000uL, request.amountSats)
        assertEquals("invoice-123", request.paymentReference)
        assertEquals("""{"order":"123"}""", request.metadata)
        assertEquals(listOf(MethodId.Bolt11.rawValue), request.acceptedPaymentEndpointIdentifiers)
    }

    @Test
    fun `refresh drops expired unsupported and non payer requests`() = test {
        whenever(paykitSdkService.actionableReceivedPaymentRequests()).thenReturn(
            listOf(
                paymentRequestRecord(expiresAt = clock.now().toString()),
                paymentRequestRecord(id = "unsupported", endpoints = listOf("btc-unsupported-method")),
                paymentRequestRecord(id = "payee", role = PaymentRequestLocalRole.PAYEE),
            ),
        )

        sut.refresh().getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
    }

    @Test
    fun `pending request is removed exactly when it expires`() = test {
        whenever(paykitSdkService.actionableReceivedPaymentRequests()).thenReturn(
            listOf(paymentRequestRecord(expiresAt = clock.now().plus(10.seconds).toString())),
        )
        sut.refresh().getOrThrow()

        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(1, sut.pendingRequests.value.size)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(sut.pendingRequests.value.isEmpty())
    }

    @Test
    fun `accept removes current request and delivers queued response`() = test {
        val record = paymentRequestRecord()
        whenever(paykitSdkService.actionableReceivedPaymentRequests()).thenReturn(listOf(record))
        whenever(
            paykitSdkService.acceptPaymentRequest(
                COUNTERPARTY,
                PaykitReceiverPaths.SERVER,
                PAYMENT_REQUEST_ID,
            ),
        ).thenReturn(record)
        sut.refresh().getOrThrow()
        clearInvocations(paykitSdkService)

        sut.accept(sut.pendingRequests.value.single()).getOrThrow()

        assertTrue(sut.pendingRequests.value.isEmpty())
        verifyBlocking(paykitSdkService) { processPendingPrivateMessages() }
    }

    @Test
    fun `expired request cannot be accepted`() = test {
        val record = paymentRequestRecord(expiresAt = clock.now().plus(1.seconds).toString())
        whenever(paykitSdkService.actionableReceivedPaymentRequests()).thenReturn(listOf(record))
        sut.refresh().getOrThrow()
        val request = sut.pendingRequests.value.single()
        advanceTimeBy(1_000)

        assertFailsWith<PaykitPaymentRequestError.RequestExpired> {
            sut.accept(request).getOrThrow()
        }
        verifyBlocking(paykitSdkService, never()) {
            acceptPaymentRequest(COUNTERPARTY, PaykitReceiverPaths.SERVER, PAYMENT_REQUEST_ID)
        }
    }

    @Suppress("LongParameterList")
    private fun paymentRequestRecord(
        id: String = PAYMENT_REQUEST_ID,
        role: PaymentRequestLocalRole? = PaymentRequestLocalRole.PAYER,
        amount: String = "0.001",
        expiresAt: String? = null,
        endpoints: List<String> = listOf(MethodId.Bolt11.rawValue),
    ) = PaymentRequestRecord(
        counterparty = COUNTERPARTY,
        counterpartyReceiverPath = PaykitReceiverPaths.SERVER,
        paymentRequestId = id,
        localRole = role,
        state = PaymentRequestLifecycleState.PROPOSED,
        proposalStreamItemId = 1uL,
        proposalOutboundMessageId = null,
        proposalOutboundStatus = null,
        proposalEventId = "proposal-event",
        terms = PaymentRequestTerms(
            amount = PaymentRequestAmount(value = amount, asset = "btc"),
            paymentReference = PAYMENT_REFERENCE,
            proposalExpiresAt = expiresAt,
            recurrence = null,
            acceptedPaymentEndpointIdentifiers = endpoints,
            metadata = METADATA,
        ),
        acceptedEventId = null,
        acceptedOutboundStatus = null,
        rejectedEventId = null,
        rejectedOutboundStatus = null,
        canceledEventId = null,
        canceledOutboundStatus = null,
        paymentProofs = emptyList(),
        lastStreamItemId = 1uL,
        lastOutboundMessageId = null,
        lastOutboundStatus = null,
        lastEventAt = clock.now().toString(),
        invalidReason = null,
    )
}
