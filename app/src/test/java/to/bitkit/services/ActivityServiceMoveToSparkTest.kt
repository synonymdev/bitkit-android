package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.updateActivity
import com.synonym.bitkitcore.upsertActivity
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.async.ServiceQueue
import to.bitkit.data.AppCacheData
import to.bitkit.data.CacheStore
import to.bitkit.data.PrivatePaykitCacheData
import to.bitkit.data.PrivatePaykitCacheStore
import to.bitkit.data.ProfileRemovalStore
import to.bitkit.ext.create
import to.bitkit.repositories.PaykitPaymentRequestRepo
import to.bitkit.repositories.PaykitReceivedPaymentContacts
import to.bitkit.repositories.PrivatePaykitAddressReservationRepo
import to.bitkit.repositories.PrivatePaykitContactResolver
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.connectionRecoveryFixture
import javax.inject.Provider
import kotlin.test.assertEquals

class ActivityServiceMoveToSparkTest : BaseUnitTest() {
    companion object {
        private const val BITKIT_CORE_FFI_CLASS = "com.synonym.bitkitcore.Bitkitcore_androidKt"
        private const val HASH = "payment-hash"
        private const val MOVED_SATS = 50_000uL
        private const val ROUTING_FEE_SATS = 3uL
    }

    private val recovery = connectionRecoveryFixture(mock())
    private val receiptCache = mock<PrivatePaykitCacheStore>()
    private val reservations = mock<PrivatePaykitAddressReservationRepo>()
    private val requests = mock<PaykitPaymentRequestRepo>()
    private val resolver by lazy {
        PrivatePaykitContactResolver(
            testDispatcher,
            receiptCache,
            Provider { reservations },
            Provider { requests },
            ProfileRemovalStore.inMemoryForTesting(),
            Provider { recovery },
        )
    }

    private suspend fun prepareReceipt() {
        whenever(receiptCache.data).thenReturn(flowOf(PrivatePaykitCacheData()))
        whenever(requests.receivedPaymentContacts).thenReturn(PaykitReceivedPaymentContacts.Empty)
        whenever(reservations.receiptAssignments()).thenReturn(
            PrivatePaykitAddressReservationRepo.ReceiptAssignments(emptyList(), 0L),
        )
    }

    private val cacheStore = mock<CacheStore>()

    private var row: Activity? = null

    @Test
    fun `a move to spark sent after its spark receive replaces it with one outgoing row and the routing fee`() =
        coreTest {
            whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
            row = Activity.Lightning(sparkReceive())

            sut().syncLdkNodePaymentsToActivities({ listOf(channelSend()) })

            val stored = (row as Activity.Lightning).v1
            assertEquals(PaymentType.SENT, stored.txType)
            assertEquals(PaymentState.SUCCEEDED, stored.status)
            assertEquals(MOVED_SATS, stored.value)
            assertEquals(ROUTING_FEE_SATS, stored.fee)
        }

    @Test
    fun `a pending move to spark send leaves its spark receive until the send succeeds`() = coreTest {
        whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
        row = Activity.Lightning(sparkReceive())

        sut().syncLdkNodePaymentsToActivities({ listOf(channelSend(PaymentStatus.PENDING)) })

        assertEquals(sparkReceive(), (row as Activity.Lightning).v1)

        sut().syncLdkNodePaymentsToActivities({ listOf(channelSend()) })

        val stored = (row as Activity.Lightning).v1
        assertEquals(PaymentType.SENT, stored.txType)
        assertEquals(PaymentState.SUCCEEDED, stored.status)
        assertEquals(MOVED_SATS, stored.value)
        assertEquals(ROUTING_FEE_SATS, stored.fee)
    }

    @Test
    fun `a move to spark settled by its spark receive takes the routing fee from the later send update`() =
        coreTest {
            whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
            // The Spark receive marked the pending send succeeded after the node's last update of it.
            row = Activity.Lightning(
                LightningActivity.create(
                    id = HASH,
                    txType = PaymentType.SENT,
                    status = PaymentState.SUCCEEDED,
                    value = MOVED_SATS,
                    invoice = "invoice",
                    timestamp = 100uL,
                    updatedAt = 300uL,
                )
            )

            sut().syncLdkNodePaymentsToActivities({ listOf(channelSend()) })

            val stored = (row as Activity.Lightning).v1
            assertEquals(PaymentState.SUCCEEDED, stored.status)
            assertEquals(ROUTING_FEE_SATS, stored.fee)
        }

    private fun coreTest(block: suspend () -> Unit) = test {
        prepareReceipt()
        ServiceQueue.CORE.background {
            mockStatic(Class.forName(BITKIT_CORE_FFI_CLASS)).use { native ->
                native.`when`<Activity?> { getActivityById(any(), any()) }.thenAnswer { row }
                native.`when`<Unit> { updateActivity(any(), any()) }.thenAnswer {
                    row = it.arguments[1] as Activity
                    null
                }
                native.`when`<Unit> { upsertActivity(any()) }.thenAnswer {
                    row = it.arguments[0] as Activity
                    null
                }
                block()
            }
        }
    }

    private fun sut() = ActivityService(
        coreService = mock(),
        cacheStore = cacheStore,
        lightningService = mock(),
        settingsStore = mock(),
        privatePaykitContactResolver = Provider { resolver },
    )

    private fun sparkReceive() = LightningActivity.create(
        id = HASH,
        txType = PaymentType.RECEIVED,
        status = PaymentState.SUCCEEDED,
        value = MOVED_SATS,
        invoice = "invoice",
        timestamp = 300uL,
        fee = 0uL,
    )

    private fun channelSend(status: PaymentStatus = PaymentStatus.SUCCEEDED) = PaymentDetails(
        id = HASH,
        kind = PaymentKind.Bolt11(
            hash = HASH,
            preimage = "pre",
            secret = null,
            description = "Move to Spark",
            bolt11 = "invoice",
        ),
        amountMsat = MOVED_SATS * 1_000uL,
        feePaidMsat = ROUTING_FEE_SATS * 1_000uL,
        direction = PaymentDirection.OUTBOUND,
        status = status,
        latestUpdateTimestamp = 200uL,
    )
}
