package to.bitkit.nearby

import org.junit.Assert.assertFalse
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
        assertTrue(isNearbyPaymentRequest("lightning:lno1qgsq"))
        assertTrue(isNearbyPaymentRequest(" ethereum:0xabc@42161/transfer?address=0xdef "))
        assertTrue(isNearbyPaymentRequest(PosNfcFixtures.SQUARE_URI))
        assertTrue(isNearbyPaymentRequest(PosNfcFixtures.BIP321_URI))
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
}
