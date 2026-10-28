package to.bitkit.repositories

import com.synonym.bitkitcore.PaymentType
import org.junit.Test
import org.lightningdevkit.ldknode.DirectFundingPaymentDetails
import org.lightningdevkit.ldknode.DirectFundingPaymentStatus
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectFundingActivityTest : BaseUnitTest() {
    private val payment = DirectFundingPaymentDetails(
        requestId = "request-id",
        paymentHash = "receipt-hash",
        amountSats = 20_000uL,
        feeSats = null,
        fundingTxid = null,
        status = DirectFundingPaymentStatus.SIGNED_PENDING,
        isOutbound = true,
        createdAt = 1_700_000_000_000uL,
    )

    @Test
    fun `confirmed conflicting spend removes the prior pending activity`() {
        val pending = directFundingActivity(payment, null, 1uL)
        val failed = directFundingActivity(payment.copy(status = DirectFundingPaymentStatus.FAILED), pending, 2uL)
        assertEquals(pending.id, failed.id)
        assertFalse(failed.doesExist)
        assertFalse(failed.confirmed)
        val restored = directFundingActivity(payment.copy(status = DirectFundingPaymentStatus.FAILED), null, 2uL)
        assertEquals(failed, restored)
    }

    @Test
    fun `pending broadcast confirmed and restart replay update one identity`() {
        val pending = directFundingActivity(payment, null, 1uL)
        val broadcast = directFundingActivity(
            payment.copy(fundingTxid = "txid", feeSats = 150uL, status = DirectFundingPaymentStatus.MEMPOOL_SEEN),
            pending.copy(seenAt = 42uL),
            2uL,
        )
        val confirmedPayment = payment.copy(
            fundingTxid = "txid",
            feeSats = 150uL,
            status = DirectFundingPaymentStatus.CONFIRMED,
        )
        val confirmed = directFundingActivity(confirmedPayment, broadcast, 3uL)
        assertEquals(pending.id, broadcast.id)
        assertEquals(pending.id, confirmed.id)
        assertEquals("direct-funding:request-id", pending.address)
        assertEquals(pending.address, confirmed.address)
        assertEquals(confirmed, directFundingActivity(confirmedPayment, confirmed, 4uL))
        assertEquals(42uL, confirmed.seenAt)
        assertEquals(20_000uL, confirmed.value)
        assertEquals(150uL, confirmed.fee)
        assertTrue(confirmed.confirmed)
        assertFalse(confirmed.isTransfer)
    }

    @Test
    fun `incoming direct funding is a channel credit not our own transfer`() {
        val received = directFundingActivity(payment.copy(isOutbound = false, feeSats = 150uL), null, 1uL)
        assertEquals(PaymentType.RECEIVED, received.txType)
        assertEquals(20_000uL, received.value)
        assertEquals(0uL, received.fee)
        assertFalse(received.isTransfer)
        assertFalse(received.confirmed)
    }
}
