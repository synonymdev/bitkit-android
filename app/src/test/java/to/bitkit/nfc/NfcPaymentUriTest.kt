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
    }

    @Test
    fun `keeps other bitcoin requests unchanged`() {
        val unified = "bitcoin:bc1qtest?lightning=lnbc1"

        assertEquals(unified, scannedPaymentRequest(unified))
        assertEquals("bitcoin:?amount=0.001", scannedPaymentRequest("bitcoin:?amount=0.001"))
        assertEquals("bitcoin:?lightning=", scannedPaymentRequest("bitcoin:?lightning="))
        assertEquals("bitcoin:?lno=lno1qgsq", scannedPaymentRequest("bitcoin:?lno=lno1qgsq"))
    }

    @Test
    fun `request repeating a parameter is kept`() {
        val invoice = PosNfcFixtures.SQUARE_INVOICE
        for (repeated in listOf(
            "bitcoin:?lightning=$invoice&amount=0.01&AMOUNT=0.02",
            "bitcoin:?lightning=$invoice&lightning=lnbc1other",
            "bitcoin:?lightning=$invoice&label=a&label=b",
            "bitcoin:?lightning=$invoice&message=a&message=b",
            "bitcoin:?lightning=$invoice&amount=0.0000799&amount=0.0000799",
            "bitcoin:?lightning=$invoice&pop=a%3a&POP=b%3a",
        )) {
            assertEquals(repeated, scannedPaymentRequest(repeated))
        }
    }

    @Test
    fun `request repeating an optional extension is unwrapped`() {
        val invoice = PosNfcFixtures.SQUARE_INVOICE
        assertEquals(
            "lightning:$invoice",
            scannedPaymentRequest("bitcoin:?lightning=$invoice&x-shop=a&X-SHOP=b&creq=c&creq=d&lno=lno1a&lno=lno1b"),
        )
    }

    @Test
    fun `request whose amount the invoice does not carry is kept`() {
        val invoice = PosNfcFixtures.SQUARE_INVOICE
        assertEquals("lightning:$invoice", scannedPaymentRequest("bitcoin:?amount=0.0000799&lightning=$invoice"))
        assertEquals("lightning:$invoice", scannedPaymentRequest("bitcoin:?amount=0.00007990&lightning=$invoice"))
        for (kept in listOf(
            "bitcoin:?amount=0.0001&lightning=$invoice",
            "bitcoin:?amount=0.0001&lightning=lnbcrt1pamountless",
            "bitcoin:?amount=abc&lightning=$invoice",
            "bitcoin:?amount=0.0000799,50&lightning=$invoice",
            "bitcoin:?amount=0.0000799BTC&lightning=$invoice",
            "bitcoin:?amount=7.99E-5&lightning=$invoice",
            "bitcoin:?amount=+0.0000799&lightning=$invoice",
        )) {
            assertEquals(kept, scannedPaymentRequest(kept))
        }
    }

    @Test
    fun `nfc read with a required parameter is refused`() {
        assertNull(nfcPaymentRequest("bitcoin:bcrt1qtest?amount=0.001&req-pop=callback%3a"))
        assertNull(nfcPaymentRequest("BITCOIN:BCRT1QTEST?REQ-FOO=bar"))
        // The scanner strips `lightning:` before decoding, so a wrapped Bitcoin request is checked as one.
        assertNull(nfcPaymentRequest("lightning:bitcoin:bcrt1qtest?amount=0.001&req-pop=callback%3a"))
        val wrappedInvoice = "LIGHTNING:bitcoin:?lightning=${PosNfcFixtures.SQUARE_INVOICE}&req-pop=callback%3a"
        assertNull(nfcPaymentRequest(wrappedInvoice))
        val required = "bitcoin:bcrt1qtest?amount=0.001&req-pop=callback"
        for (wrapper in listOf("bitkit://", "bitkit://lightning:", "lightning:lightning:", "lnurl:lightning:")) {
            assertNull(nfcPaymentRequest("$wrapper$required"))
        }
        val wrappedPlain = "bitkit://bitcoin:bcrt1qtest?amount=0.001"
        assertEquals(wrappedPlain, nfcPaymentRequest(wrappedPlain))
        val lnurlPay = "https://pay.example.com/lnurlp?req-x=1&lightning=LNURL1DP68GURN8GHJ7"
        assertEquals(lnurlPay, nfcPaymentRequest(lnurlPay))
        assertEquals("bitcoin:bcrt1qtest?amount=0.001", nfcPaymentRequest(" bitcoin:bcrt1qtest?amount=0.001 "))
        val invoice = PosNfcFixtures.SQUARE_INVOICE
        assertEquals("lightning:$invoice", nfcPaymentRequest("bitcoin:?lightning=$invoice"))
    }

    @Test
    fun `nfc read with a required parameter behind an inner bitkit scheme is refused`() {
        // The decoder deletes every `bitkit://` in a deep link, which can turn a key into `req-`.
        val address = "bitkit://bitcoin:bcrt1qtest?amount=0.001"
        assertNull(nfcPaymentRequest("$address&bitkit://req-pop=callback"))
        assertNull(nfcPaymentRequest("$address&rebitkit://q-pop=callback"))
        assertNull(nfcPaymentRequest("bitkit://bitkit://bitcoin:bcrt1qtest?REBITKIT://Q-POP=callback"))
        // The decoder deletes them again while the text starts with `bitkit://`, so a nested one is removed too.
        val required = "bitcoin:bcrt1qtest?amount=0.001&req-pop=x"
        assertNull(nfcPaymentRequest("bitkit://bitbitkit://kit://$required"))
        assertNull(nfcPaymentRequest("bitkit://lightning:bitbitkit://kit://$required"))
        // Once the text starts with `bitcoin:` the decoder deletes nothing more, so this key is not `req-`.
        val payable = "bitcoin:bcrt1qtest?amount=0.001&bitbitkit://kit://req-pop=x"
        assertEquals(payable, nfcPaymentRequest(payable))
        assertEquals("bitkit://$payable", nfcPaymentRequest("bitkit://$payable"))
    }

    @Test
    fun `request with a required parameter is kept`() {
        val invoice = PosNfcFixtures.SQUARE_INVOICE
        val required = "bitcoin:?lightning=$invoice&req-pop=callback%3a"
        assertEquals(required, scannedPaymentRequest(required))
        val uppercase = "BITCOIN:?REQ-FOO=bar&LIGHTNING=$invoice"
        assertEquals(uppercase, scannedPaymentRequest(uppercase))
        for (empty in listOf("bitcoin:?lightning=$invoice&req-foo=", "bitcoin:?lightning=$invoice&req-foo")) {
            assertEquals(empty, scannedPaymentRequest(empty))
        }
    }
}
