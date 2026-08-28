package to.bitkit.repositories

import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaykitPaymentRequestPresentationStoreTest : BaseUnitTest() {
    companion object {
        private const val IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private val KEY = Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name
    }

    @Test
    fun `saving replaces corrupt presentation state`() = test {
        val keychain = mock<Keychain>()
        var storedValue = "not-json"
        whenever(keychain.loadString(KEY)).thenAnswer { storedValue }
        whenever { keychain.upsertString(eq(KEY), any()) }.thenAnswer {
            storedValue = it.getArgument(1)
            Unit
        }
        val sut = PaykitPaymentRequestPresentationStore(keychain)
        val requestId = PaykitPaymentRequestId("request", COUNTERPARTY, "bitkit/server")

        assertTrue(sut.load(IDENTITY).isEmpty())
        sut.save(IDENTITY, setOf(requestId))

        assertEquals(setOf(requestId), sut.load(IDENTITY))
        verifyBlocking(keychain) { upsertString(KEY, storedValue) }
    }
}
