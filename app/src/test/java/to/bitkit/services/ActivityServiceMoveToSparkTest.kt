package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import com.synonym.bitkitcore.deleteActivityById
import com.synonym.bitkitcore.getActivityById
import com.synonym.bitkitcore.updateActivity
import com.synonym.bitkitcore.upsertActivity
import kotlinx.coroutines.flow.first
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
import to.bitkit.ext.create
import to.bitkit.ext.rawId
import to.bitkit.models.WalletScope
import to.bitkit.test.BaseUnitTest
import to.bitkit.test.InMemoryDataStore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ActivityServiceMoveToSparkTest : BaseUnitTest() {
    companion object {
        private const val BITKIT_CORE_FFI_CLASS = "com.synonym.bitkitcore.Bitkitcore_androidKt"
        private const val HASH = "payment-hash"
        private const val RETRIED_HASH = "retried-hash"
        private const val MOVED_SATS = 50_000uL
        private const val ROUTING_FEE_SATS = 3uL
    }

    private val cacheStore = mock<CacheStore>()

    private val rows = mutableMapOf<String, Activity>()
    private var row: Activity?
        get() = rows[HASH]
        set(value) {
            if (value == null) rows.remove(HASH) else rows[HASH] = value
        }

    @Test
    fun `a move to spark sent after its spark receive replaces it with one outgoing row and the routing fee`() =
        coreTest {
            whenever(cacheStore.data).thenReturn(flowOf(AppCacheData()))
            row = Activity.Lightning(sparkReceive())

            sut().syncLdkNodePaymentsToActivities(listOf(channelSend()))

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

        sut().syncLdkNodePaymentsToActivities(listOf(channelSend(PaymentStatus.PENDING)))

        assertEquals(sparkReceive(), (row as Activity.Lightning).v1)

        sut().syncLdkNodePaymentsToActivities(listOf(channelSend()))

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

            sut().syncLdkNodePaymentsToActivities(listOf(channelSend()))

            val stored = (row as Activity.Lightning).v1
            assertEquals(PaymentState.SUCCEEDED, stored.status)
            assertEquals(ROUTING_FEE_SATS, stored.fee)
        }

    @Test
    fun `a failed move part that smaller parts replaced leaves no row`() = coreTest {
        val cache = AppCacheData(
            moveToSparkHashes = listOf(HASH),
            deletedActivities = listOf(HASH),
        )
        assertEquals(true, cache.isActivityDeleted(HASH, WalletScope.default))
        whenever(cacheStore.data).thenReturn(flowOf(cache))
        row = Activity.Lightning(sparkReceive().copy(txType = PaymentType.SENT, status = PaymentState.FAILED))

        sut().syncLdkNodePaymentsToActivities(listOf(channelSend(PaymentStatus.FAILED)))

        assertNull(row)
    }

    @Test
    fun `a failed move part dropped while its failure was written leaves Activity again`() = coreTest {
        val before = AppCacheData(moveToSparkHashes = listOf(HASH))
        val dropped = before.copy(deletedActivities = listOf(HASH))
        // A smaller part settles and drops this one once its failure row is written.
        whenever(cacheStore.data).thenAnswer { flowOf(if (row == null) before else dropped) }

        sut().syncLdkNodePaymentsToActivities(listOf(channelSend(PaymentStatus.FAILED)))

        assertNull(row, "a failure written as its part was dropped stayed in Activity")
    }

    @Test
    fun `a pending move part dropped while it was written leaves Activity again`() = coreTest {
        val before = AppCacheData(moveToSparkHashes = listOf(HASH))
        val dropped = before.copy(deletedActivities = listOf(HASH))
        // The part fails and a smaller part settles and drops it once its pending row is written.
        whenever(cacheStore.data).thenAnswer { flowOf(if (row == null) before else dropped) }

        sut().syncLdkNodePaymentsToActivities(listOf(channelSend(PaymentStatus.PENDING)))

        assertNull(row, "a pending row written as its part was dropped stayed in Activity")
    }

    @Test
    fun `a move part that settles after a restart drops the failed part it replaced, synced before it`() = coreTest {
        val store = retriedBeforeRestart()
        rows[RETRIED_HASH] = Activity.Lightning(
            sparkReceive().copy(id = RETRIED_HASH, txType = PaymentType.SENT, status = PaymentState.FAILED),
        )

        sut(store).syncLdkNodePaymentsToActivities(
            listOf(channelSend(PaymentStatus.FAILED, RETRIED_HASH), channelSend(hash = HASH)),
        )

        assertNull(rows[RETRIED_HASH], "the failed part a settled smaller part replaced stayed in Activity")
        assertNotNull(row, "the settled part left Activity")
    }

    @Test
    fun `a move part that settles after a restart drops the failed part it replaced, synced after it`() = coreTest {
        val store = retriedBeforeRestart()

        sut(store).syncLdkNodePaymentsToActivities(
            listOf(channelSend(hash = HASH), channelSend(PaymentStatus.FAILED, RETRIED_HASH)),
        )

        assertNull(rows[RETRIED_HASH], "the failed part a settled smaller part replaced reached Activity")
        assertNotNull(row, "the settled part left Activity")
    }

    @Test
    fun `a move part that fails after a restart keeps the failed part it would have replaced`() = coreTest {
        val store = retriedBeforeRestart()

        sut(store).syncLdkNodePaymentsToActivities(
            listOf(channelSend(PaymentStatus.FAILED, RETRIED_HASH), channelSend(PaymentStatus.FAILED, HASH)),
        )

        assertNotNull(rows[RETRIED_HASH], "a failed part left Activity though nothing replaced it")
        assertEquals(emptyMap(), store.data.first().moveToSparkRetriedBefore)
    }

    private fun retriedBeforeRestart() = CacheStore(
        InMemoryDataStore(
            AppCacheData(
                moveToSparkHashes = listOf(HASH, RETRIED_HASH),
                moveToSparkRetriedBefore = mapOf(HASH to listOf(RETRIED_HASH)),
            ),
        ),
    )

    private fun coreTest(block: suspend () -> Unit) = test {
        ServiceQueue.CORE.background {
            mockStatic(Class.forName(BITKIT_CORE_FFI_CLASS)).use { native ->
                native.`when`<Activity?> { getActivityById(any(), any()) }.thenAnswer {
                    rows[it.arguments[1] as String]
                }
                native.`when`<Unit> { updateActivity(any(), any()) }.thenAnswer {
                    rows[it.arguments[0] as String] = it.arguments[1] as Activity
                    null
                }
                native.`when`<Unit> { upsertActivity(any()) }.thenAnswer {
                    val activity = it.arguments[0] as Activity
                    rows[activity.rawId()] = activity
                    null
                }
                native.`when`<Boolean> { deleteActivityById(any(), any()) }.thenAnswer {
                    rows.remove(it.arguments[1] as String) != null
                }
                block()
            }
        }
    }

    private fun sut(store: CacheStore = cacheStore) = ActivityService(
        coreService = mock(),
        cacheStore = store,
        lightningService = mock(),
        settingsStore = mock(),
        privatePaykitContactResolver = mock(),
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

    private fun channelSend(status: PaymentStatus = PaymentStatus.SUCCEEDED, hash: String = HASH) = PaymentDetails(
        id = hash,
        kind = PaymentKind.Bolt11(
            hash = hash,
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
