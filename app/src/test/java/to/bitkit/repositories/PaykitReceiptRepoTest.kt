package to.bitkit.repositories

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.paykit.IdentityStatus
import com.synonym.paykit.OutboundPrivateSendReport
import com.synonym.paykit.PubkyIdentityCapability
import com.synonym.paykit.ReceiptIssuanceStatus
import com.synonym.paykit.ReceiptIssuanceStatus.ACCESS_FAILED
import com.synonym.paykit.ReceiptIssuanceStatus.ACCESS_QUEUED
import com.synonym.paykit.ReceiptIssuanceStatus.ACCESS_SENT
import com.synonym.paykit.ReceiptIssuanceView
import dagger.Lazy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.PaykitOutboundDeliveryStore
import to.bitkit.repositories.PaykitReceiptMatcherTest.Companion.BOLT11_METHOD
import to.bitkit.repositories.PaykitReceiptMatcherTest.Companion.BUYER
import to.bitkit.repositories.PaykitReceiptMatcherTest.Companion.HASH
import to.bitkit.repositories.PaykitReceiptMatcherTest.Companion.PREIMAGE
import to.bitkit.repositories.PaykitReceiptMatcherTest.Companion.payeeRequest
import to.bitkit.repositories.PaykitReceiptMatcherTest.Companion.proof
import to.bitkit.services.PaykitSdkOperationLock.Priority
import to.bitkit.services.PaykitSdkService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaykitReceiptRepoTest : BaseUnitTest() {
    companion object {
        /** Seller identity the wallet is signed in with. */
        const val SELLER = "pubkyc3mduedw686dysw8ndr5c1dyry5h8k6i8hzbbmx9gf9k43zq4s9o"

        /** Outbound message that carries the receipt's access. */
        const val MESSAGE_ID = 7uL
    }

    private val sdk = mock<PaykitSdkService>()
    private val activityRepo = mock<ActivityRepo>()
    private val usdtPayments = mock<PaykitUsdtPaymentRepo>()
    private val delivery = mock<PaykitOutboundDeliveryStore>()
    private val sentIds = mutableSetOf<String>()
    private val accessStatus = mutableMapOf<String, ReceiptIssuanceStatus>()
    private val issued = mutableMapOf<String, ReceiptIssuanceView>()
    private val lastErrors = mutableMapOf<String, String>()
    private var drainOutcome: Boolean? = true

    private lateinit var sut: PaykitReceiptRepo

    @Before
    fun setUp() {
        sut = PaykitReceiptRepo(testDispatcher, sdk, activityRepo, Lazy { usdtPayments }, delivery)
    }

    private suspend fun stubPaidRequest(received: Boolean = true) {
        val identity = mock<IdentityStatus> {
            on { publicKey }.thenReturn(SELLER)
            on { capability }.thenReturn(PubkyIdentityCapability.PRIVATE_LINK_CAPABLE)
        }
        whenever(sdk.identityStatus(Priority.Background)).thenReturn(identity)
        val requests = listOf(payeeRequest(proofs = listOf(proof("bitcoin-bolt11-preimage", PREIMAGE, BOLT11_METHOD))))
        whenever(sdk.allPaymentRequests(SELLER, Priority.Background)).thenReturn(requests)
        val lightning = mock<LightningActivity> {
            on { id }.thenReturn(HASH)
            on { status }.thenReturn(PaymentState.SUCCEEDED)
            on { value }.thenReturn(15_000uL)
        }
        whenever(
            activityRepo.getActivities(
                anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
                anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            ),
        ).doSuspendableAnswer {
            val activities = if (received && it.arguments[1] == ActivityFilter.LIGHTNING) {
                listOf(Activity.Lightning(lightning))
            } else {
                emptyList()
            }
            Result.success(activities)
        }
        whenever(usdtPayments.receipts).thenReturn(MutableStateFlow(emptyList()))
        whenever(sdk.issuedReceipts(any())).doSuspendableAnswer { issued.values.toList() }
        whenever(delivery.isSent(eq(SELLER), any())).doSuspendableAnswer { it.getArgument<String>(1) in sentIds }
        whenever(delivery.markSent(eq(SELLER), any())).doSuspendableAnswer { sentIds += it.getArgument<String>(1) }
        whenever(sdk.processOutboundPrivateMessages(eq(BUYER), eq(Priority.Background), eq(SELLER)))
            .doSuspendableAnswer {
                drainOutcome?.let { sent -> deliverAll(if (sent) ACCESS_SENT else ACCESS_FAILED) }
                mock<OutboundPrivateSendReport>()
            }
    }

    private fun deliverAll(status: ReceiptIssuanceStatus) {
        accessStatus.replaceAll { _, current -> if (current == ACCESS_QUEUED) status else current }
    }

    private fun queue(candidate: PaykitReceiptCandidate): ReceiptIssuanceView {
        accessStatus.putIfAbsent(candidate.receiptId, ACCESS_QUEUED)
        val view = mock<ReceiptIssuanceView> {
            on { receiptId }.thenReturn(candidate.receiptId)
            on { status }.thenAnswer { accessStatus.getValue(candidate.receiptId) }
            on { lastError }.thenAnswer { lastErrors[candidate.receiptId] }
            on { outboundMessageId }.thenReturn(MESSAGE_ID)
        }
        issued[candidate.receiptId] = view
        return view
    }

    @Test
    fun `reconciles requested while one waits for the lock join it`() = test {
        stubPaidRequest()
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        val release = CompletableDeferred<Unit>()
        val running = CompletableDeferred<Unit>()
        val identity = sdk.identityStatus(Priority.Background)
        var passes = 0
        whenever(sdk.identityStatus(Priority.Background)).doSuspendableAnswer {
            passes++
            running.complete(Unit)
            release.await()
            identity
        }

        val first = async { sut.reconcile() }
        running.await()
        val burst = List(5) { async { sut.reconcile() } }
        release.complete(Unit)
        (burst + first).awaitAll()

        assertEquals(2, passes)
    }

    @Test
    fun `payment recorded while closed gets its receipt and access on the next open`() = test {
        stubPaidRequest()
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }

        sut.reconcile()

        verify(sdk).issueReceipt(any(), eq(SELLER), any())
        verify(sdk).processOutboundPrivateMessages(BUYER, Priority.Background, SELLER)
        assertEquals(mapOf(HASH to PaykitReceiptStatus.Sent), sut.statuses.value)
    }

    @Test
    fun `failed receipt waits for retry then sends`() = test {
        stubPaidRequest()
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).thenThrow(IllegalStateException("homeserver unavailable"))

        sut.reconcile()
        sut.reconcile()

        verify(sdk, times(1)).issueReceipt(any(), eq(SELLER), any())
        assertEquals(mapOf(HASH to PaykitReceiptStatus.Failed), sut.statuses.value)

        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        sut.retry(HASH.uppercase())

        verify(sdk, times(2)).issueReceipt(any(), eq(SELLER), any())
        assertEquals(mapOf(HASH to PaykitReceiptStatus.Sent), sut.statuses.value)
    }

    @Test
    fun `retry that cannot read issued receipts fails`() = test {
        stubPaidRequest()
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).thenThrow(IllegalStateException("homeserver unavailable"))
        sut.reconcile()
        assertEquals(mapOf(HASH to PaykitReceiptStatus.Failed), sut.statuses.value)

        whenever(sdk.issuedReceipts(any())).thenThrow(IllegalStateException("homeserver unavailable"))
        sut.retry(HASH)

        assertEquals(mapOf(HASH to PaykitReceiptStatus.Failed), sut.statuses.value)
    }

    @Test
    fun `receipt queued on an earlier launch is not issued again`() = test {
        stubPaidRequest()
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        sut.reconcile()

        val restarted = PaykitReceiptRepo(testDispatcher, sdk, activityRepo, Lazy { usdtPayments }, delivery)
        restarted.reconcile()

        verify(sdk, times(1)).issueReceipt(any(), eq(SELLER), any())
        assertEquals(mapOf(HASH to PaykitReceiptStatus.Sent), restarted.statuses.value)
    }

    @Test
    fun `receipt stays sending while its access is queued`() = test {
        stubPaidRequest()
        drainOutcome = null
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }

        sut.reconcile()

        assertEquals(mapOf(HASH to PaykitReceiptStatus.Sending), sut.statuses.value)
    }

    @Test
    fun `receipt fails when the sdk reports its access failed`() = test {
        stubPaidRequest()
        drainOutcome = false
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }

        sut.reconcile()

        assertEquals(mapOf(HASH to PaykitReceiptStatus.Failed), sut.statuses.value)
    }

    @Test
    fun `receipt fails when its last send attempt failed`() = test {
        stubPaidRequest()
        drainOutcome = null
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        whenever(sdk.processOutboundPrivateMessages(eq(BUYER), eq(Priority.Background), eq(SELLER)))
            .doSuspendableAnswer {
                accessStatus.keys.forEach { id -> lastErrors[id] = "peer unreachable" }
                mock<OutboundPrivateSendReport>()
            }

        sut.reconcile()

        assertEquals(mapOf(HASH to PaykitReceiptStatus.Failed), sut.statuses.value)
    }

    @Test
    fun `pass interrupted by a wipe writes nothing after it`() = test {
        stubPaidRequest()
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        whenever(sdk.processOutboundPrivateMessages(eq(BUYER), eq(Priority.Background), eq(SELLER)))
            .doSuspendableAnswer {
                deliverAll(ACCESS_SENT)
                sut.resetForWipe()
                mock<OutboundPrivateSendReport>()
            }

        sut.reconcile()

        assertTrue(sentIds.isEmpty())
        assertEquals(emptyMap(), sut.statuses.value)
    }

    @Test
    fun `receipt fails when its counterparty cannot be reached`() = test {
        stubPaidRequest()
        drainOutcome = null
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        whenever(sdk.processOutboundPrivateMessages(eq(BUYER), eq(Priority.Background), eq(SELLER)))
            .thenThrow(IllegalStateException("peer link needs recovery"))

        sut.reconcile()

        assertEquals(mapOf(HASH to PaykitReceiptStatus.Failed), sut.statuses.value)
    }

    @Test
    fun `receipt sent by another drain counts as sent`() = test {
        stubPaidRequest()
        drainOutcome = null
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        sut.reconcile()

        deliverAll(ACCESS_SENT)
        sut.reconcile()

        assertEquals(mapOf(HASH to PaykitReceiptStatus.Sent), sut.statuses.value)
        verify(delivery).markSent(eq(SELLER), any())
    }

    @Test
    fun `sent receipt is not issued again`() = test {
        stubPaidRequest()
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer { queue(it.getArgument(0)) }
        sut.reconcile()

        PaykitReceiptRepo(testDispatcher, sdk, activityRepo, Lazy { usdtPayments }, delivery).reconcile()

        verify(sdk, times(1)).issueReceipt(any(), eq(SELLER), any())
    }

    @Test
    fun `restored receipt the backup marks sent stays sent without a drain`() = test {
        stubPaidRequest()
        drainOutcome = null
        whenever(sdk.issueReceipt(any(), eq(SELLER), any())).doSuspendableAnswer {
            queue(it.getArgument(0)).also { view -> sentIds += view.receiptId }
        }

        sut.reconcile()

        assertEquals(mapOf(HASH to PaykitReceiptStatus.Sent), sut.statuses.value)
        verify(sdk, never()).processOutboundPrivateMessages(any(), any(), any())
    }

    @Test
    fun `nothing is issued before the payment is recorded`() = test {
        stubPaidRequest(received = false)

        sut.reconcile()

        verify(sdk, never()).issueReceipt(any(), eq(SELLER), any())
        assertTrue(sut.statuses.value.isEmpty())
    }
}
