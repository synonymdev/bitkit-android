package to.bitkit.repositories

import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertTrue

class PaykitPaymentProofStoreTest : BaseUnitTest() {
    companion object {
        private val KEY = Keychain.Key.PAYKIT_PENDING_PAYMENT_PROOFS.name
    }

    @Test
    fun `loading corrupt state returns no proofs`() {
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(KEY)).thenReturn("not-json")

        assertTrue(PaykitPaymentProofStore(keychain).load().isEmpty())
    }

    @Test
    fun `saving no proofs removes persisted state`() = test {
        val keychain = mock<Keychain>()

        PaykitPaymentProofStore(keychain).save(emptyList())

        verify(keychain).delete(KEY)
        verify(keychain, never()).upsertString(eq(KEY), any())
    }
}
