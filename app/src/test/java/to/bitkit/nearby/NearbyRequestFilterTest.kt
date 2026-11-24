package to.bitkit.nearby

import com.synonym.bitkitcore.AddressType
import com.synonym.bitkitcore.NetworkType
import com.synonym.bitkitcore.ValidationResult
import com.synonym.bitkitcore.validateBitcoinAddress
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.MockedStatic
import org.mockito.Mockito.mockStatic
import to.bitkit.nfc.PosNfcFixtures

class NearbyRequestFilterTest {
    private val invoice = PosNfcFixtures.SQUARE_INVOICE
    private val address = "bcrt1q60d6qvf8lufpxpw6pvdnzsplxx4edspe04nyqz"
    private val mainnetAddress = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq"
    private val corruptedAddress = address.dropLast(1) + if (address.last() == 'q') 'p' else 'q'
    private lateinit var native: MockedStatic<*>

    /** The filter validates addresses with bitkit-core, whose native library the unit tests stub. */
    @Before
    fun setUp() {
        native = mockStatic(Class.forName("com.synonym.bitkitcore.Bitkitcore_androidKt"))
        for (valid in listOf(address, mainnetAddress)) {
            val network = if (valid == mainnetAddress) NetworkType.BITCOIN else NetworkType.REGTEST
            native.`when`<ValidationResult> { validateBitcoinAddress(valid) }
                .thenReturn(ValidationResult(valid, network, AddressType.UNKNOWN))
        }
        // Like bitkit-core, which reads a bech32 network prefix in lowercase only.
        val uppercase = listOf(address.uppercase(), mainnetAddress.uppercase())
        for (invalid in listOf("bcrt1qtest", corruptedAddress, "not-an-address") + uppercase) {
            native.`when`<ValidationResult> { validateBitcoinAddress(invalid) }
                .thenThrow(IllegalArgumentException("Invalid address"))
        }
    }

    @After
    fun tearDown() = native.close()

    @Test
    fun `accepts the payment requests Bitkit serves`() {
        assertTrue(isNearbyPaymentRequest("bitcoin:$address?amount=0.001&lightning=$invoice"))
        assertTrue(isNearbyPaymentRequest("BITCOIN:${address.uppercase()}"))
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
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?lightning=LNURL1DP68GURN8GHJ7"))
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?amount=0.001&lightning=lnurl1dp68gurn8ghj7"))
        assertTrue(isNearbyPaymentRequest("bitcoin:$address?amount=0.001&lightning=$invoice"))
    }

    @Test
    fun `refuses a lightning address that starts like an invoice`() {
        assertFalse(isNearbyPaymentRequest("lightning:lnsb@example.com"))
        assertFalse(isNearbyPaymentRequest("lightning:lnbc1@example.com"))
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?lightning=lnsb@example.com"))
        assertNull(nearbyPaymentRequest("bitcoin:?lightning=lnsb@example.com"))
    }

    @Test
    fun `refuses a request with a required parameter`() {
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?amount=0.001&req-pop=callback%3a"))
        assertFalse(isNearbyPaymentRequest("BITCOIN:${address.uppercase()}?REQ-FOO=bar"))
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?lightning=$invoice&req-x"))
        assertTrue(isNearbyPaymentRequest("bitcoin:$address?amount=0.001&pop=callback%3a"))
    }

    @Test
    fun `checks every lightning value of a unified uri`() {
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?lightning=$invoice&lightning=LNURL1DP68GURN8GHJ7"))
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?lightning=LNURL1DP68GURN8GHJ7&LIGHTNING=$invoice"))
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
        assertFalse(isNearbyPaymentRequest("bitcoin:$address?lightning=$corrupted"))
    }

    @Test
    fun `refuses a bitcoin uri without an address`() {
        assertFalse(isNearbyPaymentRequest("bitcoin:"))
        assertFalse(isNearbyPaymentRequest(" BITCOIN: "))
        assertFalse(isNearbyPaymentRequest("bitcoin:not-an-address"))
        // An address that only looks right is refused too: the decoder would reject it after Send resets.
        assertFalse(isNearbyPaymentRequest("bitcoin:bcrt1qtest"))
        assertFalse(isNearbyPaymentRequest("bitcoin:$corruptedAddress?amount=0.001"))
        assertNull(nearbyPaymentRequest("bitcoin:"))
    }

    @Test
    fun `refuses an address for another network`() {
        // Tests run the dev build, a regtest wallet.
        assertFalse(isNearbyPaymentRequest("bitcoin:$mainnetAddress"))
        assertFalse(isNearbyPaymentRequest("BITCOIN:${mainnetAddress.uppercase()}?amount=0.001"))
    }

    @Test
    fun `hands over the rewritten payment request`() {
        assertEquals("lightning:$invoice", nearbyPaymentRequest(PosNfcFixtures.BIP321_URI))
        assertEquals("bitcoin:$address", nearbyPaymentRequest(" bitcoin:$address "))
    }
}
