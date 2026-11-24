package to.bitkit.nearby

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import to.bitkit.nfc.PosNfcFixtures

class NearbyRequestFilterTest {
    private val invoice = PosNfcFixtures.SQUARE_INVOICE

    @Test
    fun `accepts the payment requests Bitkit serves`() {
        assertTrue(isNearbyPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&lightning=$invoice"))
        assertTrue(isNearbyPaymentRequest("BITCOIN:BC1QTEST"))
        assertTrue(isNearbyPaymentRequest("lightning:$invoice"))
        assertTrue(isNearbyPaymentRequest("lightning:${invoice.uppercase()}"))
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
        assertTrue(isNearbyPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&lightning=$invoice"))
    }

    @Test
    fun `refuses a lightning address that starts like an invoice`() {
        assertFalse(isNearbyPaymentRequest("lightning:lnsb@example.com"))
        assertFalse(isNearbyPaymentRequest("lightning:lnbc1@example.com"))
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?lightning=lnsb@example.com"))
        assertNull(nearbyPaymentRequest("bitcoin:?lightning=lnsb@example.com"))
    }

    @Test
    fun `refuses a request with a required parameter`() {
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&req-pop=callback%3a"))
        assertFalse(isNearbyPaymentRequest("BITCOIN:BCRT1QTEST?REQ-FOO=bar"))
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?lightning=$invoice&req-x"))
        assertTrue(isNearbyPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&pop=callback%3a"))
    }

    @Test
    fun `checks every lightning value of a unified uri`() {
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?lightning=$invoice&lightning=LNURL1DP68GURN8GHJ7"))
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?lightning=LNURL1DP68GURN8GHJ7&LIGHTNING=$invoice"))
    }

    @Test
    fun `refuses what the scanner cannot pay`() {
        assertFalse(isNearbyPaymentRequest("lightning:lno1qgsq"))
        assertNull(nearbyPaymentRequest("bitcoin:?lno=lno1qgsq"))
        assertNull(nearbyPaymentRequest("BITCOIN:?amount=0.001"))
    }

    @Test
    fun `refuses an invoice whose checksum fails`() {
        val corrupted = invoice.dropLast(1) + if (invoice.last() == 'q') 'p' else 'q'
        assertFalse(isNearbyPaymentRequest("lightning:$corrupted"))
        assertFalse(isNearbyPaymentRequest("lightning:lnbc10u1p"))
        assertFalse(isNearbyPaymentRequest("lightning:lnbc1" + "q".repeat(60)))
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest?lightning=$corrupted"))
    }

    @Test
    fun `refuses a bitcoin uri without an address`() {
        assertFalse(isNearbyPaymentRequest("bitcoin:"))
        assertFalse(isNearbyPaymentRequest(" BITCOIN: "))
        assertFalse(isNearbyPaymentRequest("bitcoin:not-an-address"))
        assertNull(nearbyPaymentRequest("bitcoin:"))
    }

    @Test
    fun `hands over the rewritten payment request`() {
        assertEquals("lightning:$invoice", nearbyPaymentRequest(PosNfcFixtures.BIP321_URI))
        assertEquals("bitcoin:bcrt1qtest", nearbyPaymentRequest(" bitcoin:bcrt1qtest "))
    }
}
