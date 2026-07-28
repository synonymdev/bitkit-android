package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import to.bitkit.ext.create
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreServiceTest {

    @Test
    fun `merge hw snapshot keeps one transfer row for the same transaction`() {
        val existing = activity(
            id = "transfer",
            isTransfer = true,
            channelId = "channel",
            transferTxId = "funding",
        )
        val incoming = activity(id = "transfer")

        val result = mergeHwSnapshot(existing = listOf(existing), incoming = listOf(incoming))

        assertTrue(result.toDelete.isEmpty())
        assertEquals(1, result.toUpsert.size)
        val merged = result.toUpsert.single() as Activity.Onchain
        assertTrue(merged.v1.isTransfer)
        assertEquals("channel", merged.v1.channelId)
        assertEquals("funding", merged.v1.transferTxId)
    }

    @Test
    fun `merge hw snapshot deletes only stale non-transfer rows`() {
        val stale = activity(id = "stale")
        val transfer = activity(id = "transfer", isTransfer = true)
        val incoming = activity(id = "current")

        val result = mergeHwSnapshot(
            existing = listOf(stale, transfer),
            incoming = listOf(incoming),
        )

        assertEquals(listOf(stale), result.toDelete)
        assertEquals(listOf(incoming), result.toUpsert)
        assertFalse(result.toDelete.single().v1.isTransfer)
    }

    private fun activity(
        id: String,
        isTransfer: Boolean = false,
        channelId: String? = null,
        transferTxId: String? = null,
    ) = Activity.Onchain(
        OnchainActivity.create(
            walletId = "hardware-wallet",
            id = id,
            txType = PaymentType.RECEIVED,
            txId = id,
            value = 1uL,
            fee = 0uL,
            address = "",
            timestamp = 1uL,
            isTransfer = isTransfer,
            channelId = channelId,
            transferTxId = transferTxId,
        )
    )
}
