package to.bitkit.repositories

import kotlinx.serialization.SerializationException
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PaykitPaymentProofStoreTest : BaseUnitTest() {
    companion object {
        private val KEY = Keychain.Key.PAYKIT_PENDING_PAYMENT_PROOFS.name
    }

    @Test
    fun `loading corrupt state fails without deleting it`() = test {
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(KEY)).thenReturn("not-json")

        val error = assertFailsWith<PaykitPaymentStateUnreadableError> {
            PaykitPaymentProofStore(keychain).load()
        }
        assertContains(error.message.orEmpty(), KEY)
        assertIs<SerializationException>(error.cause)
        verify(keychain, never()).delete(KEY)
    }

    @Test
    fun `unverified txid proof stays in flight without claiming local completion`() = test {
        val identity = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        val requestId = PaykitPaymentRequestId("request", "counterparty")
        val proof = PendingPaykitPaymentProof(
            identity = identity, requestId = requestId, paymentEndpointIdentifier = MethodId.P2wpkh.rawValue, paymentAppId = "bitkit",
            kind = PaykitPaymentProofKind.Onchain, paymentStarted = true,
            paymentIdentifier = "ab".repeat(32), proofData = "ab".repeat(32),
        )
        var saved: String? = null
        val keychain = mock<Keychain>()
        whenever(keychain.loadString(KEY)).thenAnswer { saved }
        whenever(keychain.upsertString(eq(KEY), any())).doSuspendableAnswer { saved = it.getArgument(1) }
        val store = PaykitPaymentProofStore(keychain)
        store.save(listOf(proof))
        assertTrue(store.completedRequestProofKindsAwaitingSubmission(identity).isEmpty())
        assertEquals(setOf(requestId), store.inFlightRequestIds(identity))

        store.save(listOf(proof.copy(onchainAcceptanceVerified = true)))
        assertContains(requireNotNull(saved), "\"onchainAcceptanceVerified\":true")
        assertEquals(mapOf(requestId to PaykitPaymentProofKind.Onchain), store.completedRequestProofKindsAwaitingSubmission(identity))
    }

    @Test
    fun `saving no proofs removes persisted state`() = test {
        val keychain = mock<Keychain>()

        PaykitPaymentProofStore(keychain).save(emptyList())

        verify(keychain).delete(KEY)
        verify(keychain, never()).upsertString(eq(KEY), any())
    }
}
