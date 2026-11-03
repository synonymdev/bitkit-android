package to.bitkit.services

import android.content.Context
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.create
import to.bitkit.repositories.ActivityRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationServiceTest : BaseUnitTest() {

    @Test
    fun `missing transfer markers remain pending until their activity exists`() = test {
        val activityRepo = mock<ActivityRepo>()
        val service = createService(activityRepo)
        val markers = mapOf("transfer" to "channel")

        val pending = service.applyRemoteTransfers(markers)
        assertEquals(markers, pending)
        verify(activityRepo, never()).updateActivity(any(), any(), any())

        val activity = onchain("transfer")
        whenever(activityRepo.getOnchainActivityByTxId("transfer")).thenReturn(activity)
        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.success(Unit))
        assertTrue(service.applyRemoteTransfers(pending).isEmpty())
        verify(activityRepo).updateActivity(
            "transfer",
            Activity.Onchain(activity.copy(isTransfer = true, channelId = "channel")),
        )
    }

    @Test
    fun `missing boost markers remain pending until their activities exist`() = test {
        val activityRepo = mock<ActivityRepo>()
        val service = createService(activityRepo)
        val markers = mapOf("parent" to "child")
        val pending = service.applyBoostTransactions(markers)
        assertEquals(markers, pending)

        whenever(activityRepo.getOnchainActivityByTxId("parent")).thenReturn(onchain("parent"))
        whenever(activityRepo.getOnchainActivityByTxId("child")).thenReturn(onchain("child"))
        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.success(Unit))
        assertTrue(service.applyBoostTransactions(pending).isEmpty())
    }

    @Test
    fun `failed transfer and boost writes remain pending`() = test {
        val activityRepo = mock<ActivityRepo>()
        val service = createService(activityRepo)
        whenever(activityRepo.getOnchainActivityByTxId(any(), any())).thenReturn(onchain("activity"))
        whenever(activityRepo.updateActivity(any(), any(), any())).thenReturn(Result.failure(AppError("write failed")))

        val transfer = mapOf("transfer" to "channel")
        val boost = mapOf("parent" to "child")
        assertEquals(transfer, service.applyRemoteTransfers(transfer))
        assertEquals(boost, service.applyBoostTransactions(boost))
    }

    private fun createService(activityRepo: ActivityRepo): MigrationService {
        val context = mock<Context>()
        whenever(context.applicationContext).thenReturn(context)
        return MigrationService(context, mock(), mock(), mock(), mock(), activityRepo, mock(), mock(), mock(), mock())
    }

    private fun onchain(id: String) = OnchainActivity.create(
        walletId = "wallet0",
        id = id,
        txType = PaymentType.SENT,
        txId = id,
        value = 1000uL,
        fee = 100uL,
        feeRate = 1uL,
        address = "address",
        timestamp = 1uL,
    )

    @Test
    fun `fetchOrdersInChunks should split ids into chunks and concatenate results`() = runTest {
        val orderIds = (1..25).map { "order$it" }
        val requests = mutableListOf<List<String>>()

        val result = fetchOrdersInChunks(orderIds) { ids, _ ->
            requests += ids
            ids.map { orderId -> mock<IBtOrder> { on { id } doReturn orderId } }
        }

        assertEquals(listOf(20, 5), requests.map { it.size })
        assertEquals(orderIds, requests.flatten())
        assertEquals(orderIds, result.map { it.id })
    }

    @Test
    fun `fetchOrdersInChunks should refresh active orders only on the first chunk`() = runTest {
        val orderIds = (1..45).map { "order$it" }
        val refreshFlags = mutableListOf<Boolean>()

        fetchOrdersInChunks(orderIds) { _, refreshActive ->
            refreshFlags += refreshActive
            emptyList()
        }

        assertEquals(listOf(true, false, false), refreshFlags)
    }

    @Test
    fun `fetchOrdersInChunks should not fetch when ids are empty`() = runTest {
        val requests = mutableListOf<List<String>>()

        val result = fetchOrdersInChunks(emptyList()) { ids, _ ->
            requests += ids
            emptyList()
        }

        assertTrue(requests.isEmpty())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `unapplied metadata keeps only tags whose activity is missing`() {
        val metadata = RNMetadata(
            tags = mapOf("known" to listOf("sent"), "missing" to listOf("received")),
            lastUsedTags = listOf("sent"),
        )

        val remaining = unappliedRnMetadata(metadata, setOf("missing"))

        assertEquals(mapOf("missing" to listOf("received")), remaining?.tags)
        assertEquals(null, remaining?.lastUsedTags)
    }

    @Test
    fun `unapplied metadata is dropped when every tag was applied`() {
        val metadata = RNMetadata(tags = mapOf("known" to listOf("sent")), lastUsedTags = listOf("sent"))

        assertEquals(null, unappliedRnMetadata(metadata, emptySet()))
    }

    @Test
    fun `unapplied metadata is dropped when there are no tags`() {
        val metadata = RNMetadata(tags = null, lastUsedTags = listOf("sent"))

        assertEquals(null, unappliedRnMetadata(metadata, setOf("missing")))
    }
}
