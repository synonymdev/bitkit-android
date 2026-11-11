package to.bitkit.services

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.synonym.bitkitcore.Scanner
import com.synonym.bitkitcore.decode
import org.junit.Test
import org.junit.runner.RunWith
import to.bitkit.ext.runSuspendCatching
import to.bitkit.test.BaseAndroidTest
import to.bitkit.test.annotations.CoreServiceIntegration
import to.bitkit.test.annotations.DeviceIntegration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@DeviceIntegration
@CoreServiceIntegration
class Bip21DecodingTest : BaseAndroidTest() {
    companion object {
        /** Valid regtest recipient from the bitkit-core regression fixture. */
        private const val ADDRESS = "bcrt1qr289x0fhg62672e8urudfnxnsr8tcax64xk2vk"
    }

    @Test
    fun coreRejectsDuplicateSingletonParameters() = test {
        val duplicatedUri = "bitcoin:$ADDRESS?amount=0.0000002&message=Bitkit" +
            "bitcoin:$ADDRESS?amount=0.0000003&message=Bitkit"
        for (uri in listOf(
            duplicatedUri,
            "bitcoin:$ADDRESS?amount=0.000035&AMOUNT=0.00005",
            "bitcoin:$ADDRESS?label=first&LABEL=second",
            "bitcoin:$ADDRESS?message=first&Message=second",
            "bitcoin:$ADDRESS?pop=callback%3a&req-pop=callback%3a",
        )) {
            assertTrue(runSuspendCatching { decode(uri) }.isFailure, uri)
        }
    }

    @Test
    fun corePreservesBitcoinTextInQueryMetadata() = test {
        for ((key, value) in listOf(
            "message" to "bitcoin:donation",
            "label" to "BITCOIN:donation",
            "custom" to "bitcoin:1BoatSLRHtKNngkdXEeobR76b53LETtpyT",
            "message" to "Why?",
        )) {
            val decoded = assertIs<Scanner.OnChain>(decode("bitcoin:$ADDRESS?amount=0.000035&$key=$value"))
            assertEquals(ADDRESS, decoded.invoice.address)
            assertEquals(3500uL, decoded.invoice.amountSatoshis)
            assertEquals(value, decoded.invoice.params?.get(key))
        }
    }
}
