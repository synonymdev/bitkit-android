@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import kotlinx.serialization.SerializationException
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class PaykitPaymentRequestPresentationStoreTest : BaseUnitTest() {
    companion object {
        private const val IDENTITY = "pubky1rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private const val COUNTERPARTY = "pubky3rsduhcxpw74snwyct86m38c63j3pq8x4ycqikxg64roik8yw5xg"
        private val KEY = Keychain.Key.PAYKIT_PRESENTED_PAYMENT_REQUESTS.name
    }

    @Test
    fun `loading or saving corrupt subscription state preserves it`() = test {
        val keychain = mock<Keychain>()
        var storedValue = "not-json"
        whenever(keychain.loadString(KEY)).thenAnswer { storedValue }
        whenever { keychain.upsertString(eq(KEY), any()) }.thenAnswer {
            storedValue = it.getArgument(1)
            Unit
        }
        val sut = PaykitPaymentRequestPresentationStore(keychain)
        val requestId = PaykitPaymentRequestId("request", COUNTERPARTY, "bitkit/server")

        assertFailsWith<SerializationException> { sut.load(IDENTITY) }
        assertFailsWith<SerializationException> { sut.save(IDENTITY, setOf(requestId)) }
        assertFailsWith<SerializationException> { sut.backupSnapshot() }
        assertEquals("not-json", storedValue)
        verify(keychain, never()).upsertString(eq(KEY), any())
    }

    @Test
    fun `only portable subscription changes update backup state`() = test {
        val keychain = mock<Keychain>()
        var storedValue: String? = null
        whenever(keychain.loadString(KEY)).thenAnswer { storedValue }
        whenever { keychain.upsertString(eq(KEY), any()) }.thenAnswer {
            storedValue = it.getArgument(1)
            Unit
        }
        val sut = PaykitPaymentRequestPresentationStore(keychain)
        val requestId = PaykitPaymentRequestId("request", COUNTERPARTY, "bitkit/server")
        val subscriptionId = PaykitSubscriptionId("subscription", COUNTERPARTY, "bitkit/server")
        val dismissedOnly = PaykitSubscriptionPresentationState(dismissedPaymentIds = setOf(requestId))
        val accepted = dismissedOnly.copy(
            acceptedAt = mapOf(subscriptionId to Instant.parse("2026-09-24T08:00:00Z")),
        )

        sut.save(IDENTITY, setOf(requestId))
        sut.saveSubscriptionState(IDENTITY, dismissedOnly)
        assertEquals(0L, sut.backupStateVersion.value)
        assertEquals(emptyMap(), sut.backupSnapshot())

        sut.saveSubscriptionState(IDENTITY, accepted)
        assertEquals(1L, sut.backupStateVersion.value)

        sut.saveSubscriptionState(IDENTITY, accepted)
        assertEquals(1L, sut.backupStateVersion.value)

        sut.saveSubscriptionState(
            IDENTITY,
            accepted.copy(presentedProposalIds = setOf(subscriptionId)),
        )
        assertEquals(2L, sut.backupStateVersion.value)
    }
}
