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
}
