package to.bitkit.models

import org.junit.Test
import org.lightningdevkit.ldknode.DirectFundingPaymentStatus
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectFundingTest {
    @Test
    fun `ordinary invoice has no direct funding parameter`() {
        assertNull(directFundingParameter("bitcoin:address?amount=0.001&lightning=lnbc1test").getOrThrow())
    }

    @Test
    fun `direct funding preserves signed envelope characters`() {
        assertEquals("Ab0-_x", directFundingParameter("bitcoin:address?bgnq=Ab0-_x").getOrThrow())
        assertEquals("Ab0-_x", directFundingParameter("bitcoin:address?%62gnq=Ab0-_x").getOrThrow())
    }

    @Test
    fun `empty malformed and repeated requests cannot fall back`() {
        listOf(
            "bgnq=", "bgnq", "bgnq=a&bgnq=b", "bgnq=a&BGNQ=b", "bgnq=a&%62gnq=b",
            "bgnq=a+b", "bgnq=a%2Fb", "bgnq=a%3Db", "bgnq=%zz",
        ).forEach { query ->
            assertTrue(directFundingParameter("bitcoin:address?lightning=valid&$query").isFailure, query)
        }
    }

    @Test
    fun `adding direct funding preserves both fallback rails and amount`() {
        assertEquals(
            "bitcoin:address?amount=0.001&lightning=lnbc1test&bgnq=signed",
            appendDirectFundingParameter("bitcoin:address?amount=0.001&lightning=lnbc1test", "signed"),
        )
        assertEquals("bitcoin:address?bgnq=signed", appendDirectFundingParameter("address", "signed"))
    }

    @Test
    fun `only definitive pre-sign failures permit another payment`() {
        DirectFundingPaymentStatus.entries.forEach {
            assertEquals(
                it in setOf(
                    DirectFundingPaymentStatus.OFFERED, DirectFundingPaymentStatus.SIGNED_PENDING,
                    DirectFundingPaymentStatus.MEMPOOL_SEEN, DirectFundingPaymentStatus.CONFIRMED,
                    DirectFundingPaymentStatus.FAILED,
                ),
                it.blocksAnotherPayment()
            )
        }
        assertFalse(DirectFundingPaymentStatus.ABORTED.blocksAnotherPayment())
    }

    @Test
    fun `failed outbound retains commitment but failed inbound negotiation does not`() {
        assertTrue(DirectFundingPaymentStatus.FAILED.hasCommittedFunding(isOutbound = true))
        assertFalse(DirectFundingPaymentStatus.FAILED.hasCommittedFunding(isOutbound = false))
        assertFalse(DirectFundingPaymentStatus.ABORTED.hasCommittedFunding())
    }
}
