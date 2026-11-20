package to.bitkit.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.bitkit.nfc.PosNfcFixtures

class NearbyRequestFilterTest {
    @Test
    fun `accepts the payment requests Bitkit serves`() {
        assertTrue(isNearbyPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&lightning=lnbcrt1p"))
        assertTrue(isNearbyPaymentRequest("BITCOIN:BC1QTEST"))
        assertTrue(isNearbyPaymentRequest("lightning:lnbc10u1p"))
        assertTrue(isNearbyPaymentRequest("lightning:LNBCRT1P"))
        assertTrue(isNearbyPaymentRequest(" ethereum:0xabc@42161/transfer?address=0xdef "))
        assertTrue(isNearbyPaymentRequest(PosNfcFixtures.SQUARE_URI))
    }

    @Test
    fun `refuses everything else the scanner understands`() {
        assertFalse(isNearbyPaymentRequest("pubkyauth:///?caps=/pub/:rw&secret=x&relay=https://r"))
        assertFalse(isNearbyPaymentRequest("lightning:LNURL1DP68GURN8GHJ7"))
        assertFalse(isNearbyPaymentRequest("lnurlw://example.com/withdraw"))
        assertFalse(isNearbyPaymentRequest("keyauth://example.com"))
        assertFalse(isNearbyPaymentRequest("https://btcpay.example.com/samrock"))
        assertFalse(isNearbyPaymentRequest("pk:8pinxxgqs41n4aididenw5apqp1urfmzdztr8jt4abrkdn435ewo"))
        assertFalse(isNearbyPaymentRequest("lnbc10u1p"))
        assertFalse(isNearbyPaymentRequest(""))
    }

    @Test
    fun `refuses a non-payment request wrapped in a bitcoin uri`() {
        assertNull(nearbyPaymentRequest("bitcoin:?lightning=LNURL1DP68GURN8GHJ7"))
        assertNull(nearbyPaymentRequest("BITCOIN:?LIGHTNING=lnurl1dp68gurn8ghj7"))
    }

    @Test
    fun `refuses a unified uri whose lightning request would be fetched`() {
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?lightning=LNURL1DP68GURN8GHJ7"))
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&lightning=lnurl1dp68gurn8ghj7"))
        assertTrue(isNearbyPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&lightning=lnbcrt1p"))
    }

    @Test
    fun `refuses what the scanner cannot pay`() {
        assertFalse(isNearbyPaymentRequest("lightning:lno1qgsq"))
        assertNull(nearbyPaymentRequest("bitcoin:?lno=lno1qgsq"))
        assertNull(nearbyPaymentRequest("BITCOIN:?amount=0.001"))
    }

    @Test
    fun `hands over the rewritten payment request`() {
        val invoice = PosNfcFixtures.SQUARE_INVOICE
        assertEquals("lightning:$invoice", nearbyPaymentRequest(PosNfcFixtures.BIP321_URI))
        assertEquals("bitcoin:bcrt1qtest", nearbyPaymentRequest(" bitcoin:bcrt1qtest "))
    }
}
