package to.bitkit.services

import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.LightningActivity
import com.synonym.bitkitcore.OnchainActivity
import com.synonym.bitkitcore.PaymentState
import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentStatus
import to.bitkit.ext.create
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

        val result = mergePlan(existing = listOf(existing), incoming = listOf(incoming))

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

        val result = mergePlan(
            existing = listOf(stale, transfer),
            incoming = listOf(incoming),
        )

        assertEquals(listOf(stale), result.toDelete)
        assertEquals(listOf(incoming), result.toUpsert)
        assertFalse(result.toDelete.single().v1.isTransfer)
    }

    @Test
    fun `merge hw snapshot keeps locally created pending send until watcher reports it`() {
        val pendingSend = activity(id = "pendingSend").let {
            Activity.Onchain(
                it.v1.copy(
                    txType = PaymentType.SENT,
                    confirmed = false,
                    createdAt = 100_000uL,
                )
            )
        }

        val result = mergePlan(
            existing = listOf(pendingSend),
            incoming = emptyList(),
        )

        assertTrue(result.toDelete.isEmpty())
    }

    @Test
    fun `merge hw snapshot deletes expired pending send missing from snapshot`() {
        val pendingSend = activity(id = "pendingSend").let {
            Activity.Onchain(
                it.v1.copy(
                    txType = PaymentType.SENT,
                    confirmed = false,
                    createdAt = 1uL,
                )
            )
        }

        val result = mergePlan(existing = listOf(pendingSend), incoming = emptyList())

        assertEquals(listOf(pendingSend), result.toDelete)
    }

    @Test
    fun `merge hw snapshot recovers transfer from known funding tx when no stored row remains`() {
        val result = mergePlan(
            existing = emptyList(),
            incoming = listOf(activity(id = "fundingTx")),
            transferChannelIdsByFundingTxId = mapOf("fundingTx" to "channel-1"),
        )

        val recovered = result.upserted("fundingTx")
        assertEquals(true, recovered?.isTransfer)
        assertEquals("channel-1", recovered?.channelId)
    }

    @Test
    fun `merge hw snapshot leaves unrelated transaction unmarked`() {
        val result = mergePlan(
            existing = emptyList(),
            incoming = listOf(activity(id = "someOtherTx")),
            transferChannelIdsByFundingTxId = mapOf("fundingTx" to "channel-1"),
        )

        val untouched = result.upserted("someOtherTx")
        assertEquals(false, untouched?.isTransfer)
        assertNull(untouched?.channelId)
    }

    @Test
    fun `merge hw snapshot keeps stored channel id over recovered channel id`() {
        val result = mergePlan(
            existing = listOf(activity(id = "fundingTx", isTransfer = true, channelId = "stored-channel")),
            incoming = listOf(activity(id = "fundingTx")),
            transferChannelIdsByFundingTxId = mapOf("fundingTx" to "channel-1"),
        )

        val merged = result.upserted("fundingTx")
        assertEquals(true, merged?.isTransfer)
        assertEquals("stored-channel", merged?.channelId)
    }

    @Test
    fun `merge hw snapshot keeps stored contact`() {
        val result = mergePlan(
            existing = listOf(activityWithContact(id = "tx", contact = "pubky-contact")),
            incoming = listOf(activity(id = "tx")),
        )

        assertEquals("pubky-contact", result.upserted("tx")?.contact)
    }

    @Test
    fun `merge hw snapshot keeps stored seen timestamp`() {
        val result = mergePlan(
            existing = listOf(Activity.Onchain(activity(id = "tx").v1.copy(seenAt = 42uL))),
            incoming = listOf(activity(id = "tx")),
        )

        assertEquals(42uL, result.upserted("tx")?.seenAt)
    }

    @Test
    fun `merge hw snapshot fills missing channel id on stored transfer`() {
        val result = mergePlan(
            existing = listOf(activity(id = "fundingTx", isTransfer = false, channelId = null)),
            incoming = listOf(activity(id = "fundingTx")),
            transferChannelIdsByFundingTxId = mapOf("fundingTx" to "channel-1"),
        )

        val merged = result.upserted("fundingTx")
        assertEquals(true, merged?.isTransfer)
        assertEquals("channel-1", merged?.channelId)
    }

    @Test
    fun `merge hw snapshot recovers transfer matching on tx id not activity id`() {
        val result = mergePlan(
            existing = emptyList(),
            incoming = listOf(activity(id = "rebuilt-activity-id", txId = "fundingTx")),
            transferChannelIdsByFundingTxId = mapOf("fundingTx" to "channel-1"),
        )

        val recovered = result.upserted("rebuilt-activity-id")
        assertEquals(true, recovered?.isTransfer)
        assertEquals("channel-1", recovered?.channelId)
    }

    @Test
    fun `merge hw snapshot leaves activities unchanged without known transfers`() {
        val result = mergePlan(existing = emptyList(), incoming = listOf(activity(id = "fundingTx")))

        val untouched = result.upserted("fundingTx")
        assertEquals(false, untouched?.isTransfer)
        assertNull(untouched?.channelId)
    }

    @Test
    fun `merge hw snapshot leaves lightning activities untouched`() {
        val lightning = lightningActivity(id = "fundingTx")

        val result = mergePlan(
            existing = emptyList(),
            incoming = listOf(lightning),
            transferChannelIdsByFundingTxId = mapOf("fundingTx" to "channel-1"),
        )

        assertEquals(listOf(lightning), result.toUpsert)
    }

    @Test
    fun `payment update refreshes amount fee preimage and status on successful retry`() {
        val failed = failedSend(value = 300_000uL, fee = 5uL, preimage = null)

        val result = failed.withPaymentUpdate(
            payment = payment(amountMsat = 22_000uL, feePaidMsat = 1_999uL, latestUpdateTimestamp = 200uL),
            kind = bolt11(preimage = "preimage"),
            state = PaymentState.SUCCEEDED,
            contact = "contact",
        )

        assertEquals(22uL, result.value)
        assertEquals(1uL, result.fee)
        assertEquals("preimage", result.preimage)
        assertEquals(PaymentState.SUCCEEDED, result.status)
        assertEquals(200uL, result.updatedAt)
        assertEquals("contact", result.contact)
    }

    @Test
    fun `payment update keeps untouched fields of the existing row`() {
        val failed = failedSend(value = 300_000uL, fee = 5uL, preimage = null)

        val result = failed.withPaymentUpdate(
            payment = payment(amountMsat = 22_000uL, feePaidMsat = 1_000uL, latestUpdateTimestamp = 200uL),
            kind = bolt11(preimage = "preimage", bolt11 = "other-invoice"),
            state = PaymentState.SUCCEEDED,
            contact = null,
        )

        assertEquals(failed.id, result.id)
        assertEquals(failed.txType, result.txType)
        assertEquals(failed.timestamp, result.timestamp)
        assertEquals(failed.invoice, result.invoice)
        assertEquals(failed.seenAt, result.seenAt)
        assertEquals(failed.createdAt, result.createdAt)
    }

    @Test
    fun `payment update keeps prior fee when fee paid is unknown`() {
        val result = failedSend(fee = 5uL).withPaymentUpdate(
            payment = payment(feePaidMsat = null),
            kind = bolt11(),
            state = PaymentState.SUCCEEDED,
            contact = null,
        )

        assertEquals(5uL, result.fee)
    }

    @Test
    fun `payment update keeps prior value when amount is unknown`() {
        val result = failedSend(value = 300_000uL).withPaymentUpdate(
            payment = payment(amountMsat = null),
            kind = bolt11(),
            state = PaymentState.SUCCEEDED,
            contact = null,
        )

        assertEquals(300_000uL, result.value)
    }

    @Test
    fun `payment update keeps prior preimage when retry has none`() {
        val result = failedSend(preimage = "old-preimage").withPaymentUpdate(
            payment = payment(),
            kind = bolt11(preimage = null),
            state = PaymentState.PENDING,
            contact = null,
        )

        assertEquals("old-preimage", result.preimage)
    }

    @Test
    fun `payment update keeps prior message when description is a description hash`() {
        val result = failedSend(message = "lnurl comment").withPaymentUpdate(
            payment = payment(),
            kind = bolt11(description = "a".repeat(64)),
            state = PaymentState.SUCCEEDED,
            contact = null,
        )

        assertEquals("lnurl comment", result.message)
    }

    @Test
    fun `payment update keeps prior message when description differs`() {
        val result = failedSend(message = "coffee").withPaymentUpdate(
            payment = payment(),
            kind = bolt11(description = "tea"),
            state = PaymentState.SUCCEEDED,
            contact = null,
        )

        assertEquals("coffee", result.message)
    }

    private fun mergePlan(
        existing: List<Activity.Onchain>,
        incoming: List<Activity>,
        currentTimestamp: ULong = 100_000uL,
        transferChannelIdsByFundingTxId: Map<String, String> = emptyMap(),
    ) = mergeHwSnapshot(
        existing = existing,
        incoming = incoming,
        currentTimestamp = currentTimestamp,
        transferChannelIdsByFundingTxId = transferChannelIdsByFundingTxId,
    )

    private fun HwSnapshotMerge.upserted(id: String): OnchainActivity? =
        toUpsert.filterIsInstance<Activity.Onchain>().firstOrNull { it.v1.id == id }?.v1

    private fun activity(
        id: String,
        txId: String = id,
        isTransfer: Boolean = false,
        channelId: String? = null,
        transferTxId: String? = null,
    ) = Activity.Onchain(
        OnchainActivity.create(
            walletId = "hardware-wallet",
            id = id,
            txType = PaymentType.RECEIVED,
            txId = txId,
            value = 1uL,
            fee = 0uL,
            address = "",
            timestamp = 1uL,
            isTransfer = isTransfer,
            channelId = channelId,
            transferTxId = transferTxId,
        )
    )

    private fun activityWithContact(id: String, contact: String) = Activity.Onchain(
        activity(id).v1.copy(contact = contact)
    )

    private fun lightningActivity(id: String) = Activity.Lightning(
        LightningActivity.create(
            walletId = "hardware-wallet",
            id = id,
            txType = PaymentType.RECEIVED,
            status = PaymentState.SUCCEEDED,
            value = 1uL,
            invoice = "",
            timestamp = 1uL,
        )
    )

    private fun failedSend(
        value: ULong = 300_000uL,
        fee: ULong = 0uL,
        message: String = "",
        preimage: String? = null,
    ) = LightningActivity.create(
        walletId = "wallet",
        id = "payment-hash",
        txType = PaymentType.SENT,
        status = PaymentState.FAILED,
        value = value,
        invoice = "invoice",
        timestamp = 100uL,
        fee = fee,
        message = message,
        preimage = preimage,
        seenAt = 150uL,
    )

    private fun payment(
        amountMsat: ULong? = 22_000uL,
        feePaidMsat: ULong? = 1_000uL,
        latestUpdateTimestamp: ULong = 200uL,
    ) = PaymentDetails(
        id = "payment-hash",
        kind = bolt11(),
        amountMsat = amountMsat,
        feePaidMsat = feePaidMsat,
        direction = PaymentDirection.OUTBOUND,
        status = PaymentStatus.SUCCEEDED,
        latestUpdateTimestamp = latestUpdateTimestamp,
    )

    private fun bolt11(
        preimage: String? = null,
        description: String? = null,
        bolt11: String? = "invoice",
    ) = PaymentKind.Bolt11(
        hash = "payment-hash",
        preimage = preimage,
        secret = null,
        description = description,
        bolt11 = bolt11,
    )
}
