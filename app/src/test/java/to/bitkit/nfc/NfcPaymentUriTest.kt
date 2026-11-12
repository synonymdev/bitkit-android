package to.bitkit.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcPaymentUriTest {
    @Test
    fun `keeps requests that already have a payment scheme`() {
        assertEquals("bitcoin:bc1q?lightning=lnbc1", nfcPaymentUri("bitcoin:bc1q?lightning=lnbc1"))
        assertEquals("BITCOIN:BC1Q", nfcPaymentUri("BITCOIN:BC1Q"))
        assertEquals("lightning:lnbc1", nfcPaymentUri("lightning:lnbc1"))
        assertEquals("ethereum:0xabc@42161/transfer", nfcPaymentUri("ethereum:0xabc@42161/transfer"))
        assertEquals("LIGHTNING:LNTBS1P", nfcPaymentUri("LIGHTNING:LNTBS1P"))
    }

    @Test
    fun `adds lightning to bare lightning requests`() {
        assertEquals("lightning:lnbc10u1p", nfcPaymentUri("lnbc10u1p"))
        assertEquals("lightning:lnbcrt1p", nfcPaymentUri(" lnbcrt1p "))
        assertEquals("lightning:LNURL1DP68", nfcPaymentUri("LNURL1DP68"))
    }

    @Test
    fun `adds bitcoin to bare addresses`() {
        assertEquals("bitcoin:bc1qxyz", nfcPaymentUri("bc1qxyz"))
    }

    @Test
    fun `returns null for no request`() {
        assertNull(nfcPaymentUri(null))
        assertNull(nfcPaymentUri("  "))
    }

    @Test
    fun `keeps a Square request for the scanner`() {
        assertEquals(PosNfcFixtures.SQUARE_URI, scannedPaymentRequest(PosNfcFixtures.SQUARE_URI))
        assertEquals("LIGHTNING:LNTBS1P", scannedPaymentRequest(" LIGHTNING:LNTBS1P\n"))
    }

    @Test
    fun `turns a BIP 321 request without an address into its Lightning request`() {
        val invoice = PosNfcFixtures.SQUARE_INVOICE

        assertEquals("lightning:$invoice", scannedPaymentRequest(PosNfcFixtures.BIP321_URI))
        assertEquals("lightning:$invoice", scannedPaymentRequest("BITCOIN:?LIGHTNING=$invoice"))
        assertEquals("lightning:$invoice", scannedPaymentRequest("bitcoin:?amount=0.0000799&lightning=$invoice&creq=x"))
        assertEquals("lightning:lno1qgsq", scannedPaymentRequest("bitcoin:?lno=lno1qgsq"))
    }

    @Test
    fun `keeps other bitcoin requests unchanged`() {
        val unified = "bitcoin:bc1qtest?lightning=lnbc1"

        assertEquals(unified, scannedPaymentRequest(unified))
        assertEquals("bitcoin:?amount=0.001", scannedPaymentRequest("bitcoin:?amount=0.001"))
        assertEquals("bitcoin:?lightning=", scannedPaymentRequest("bitcoin:?lightning="))
    }
}
