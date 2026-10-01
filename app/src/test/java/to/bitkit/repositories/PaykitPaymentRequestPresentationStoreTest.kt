@file:OptIn(ExperimentalTime::class)

package to.bitkit.repositories

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PaykitPaymentStateBackup
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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

        val loadError = assertFailsWith<PaykitPaymentStateUnreadableError> { sut.load(IDENTITY) }
        val saveError = assertFailsWith<PaykitPaymentStateUnreadableError> { sut.save(IDENTITY, setOf(requestId)) }
        val backupError = assertFailsWith<PaykitPaymentStateUnreadableError> { sut.backupSnapshot() }
        listOf(loadError, saveError, backupError).forEach {
            assertContains(it.message.orEmpty(), KEY)
            assertIs<SerializationException>(it.cause)
        }
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
            acceptedAt = mapOf(subscriptionId to Instant.parse("2026-09-24T08:00:00.123Z")),
        )

        sut.save(IDENTITY, setOf(requestId))
        sut.saveSubscriptionState(IDENTITY, dismissedOnly)
        assertEquals(0L, sut.backupStateVersion.value)
        assertEquals(emptyMap(), sut.backupSnapshot())

        sut.saveSubscriptionState(IDENTITY, accepted)
        assertEquals(1L, sut.backupStateVersion.value)
        assertEquals(
            "2026-09-24T08:00:00.123Z",
            sut.backupSnapshot().getValue(IDENTITY).acceptances.single().acceptedAt,
        )

        sut.saveSubscriptionState(IDENTITY, accepted)
        assertEquals(1L, sut.backupStateVersion.value)

        sut.saveSubscriptionState(
            IDENTITY,
            accepted.copy(presentedProposalIds = setOf(subscriptionId)),
        )
        assertEquals(2L, sut.backupStateVersion.value)
    }

    @Test
    fun `backup restore preserves precise acceptance billing boundaries`() = test {
        val keychain = mock<Keychain>()
        var storedValue: String? = null
        whenever(keychain.loadString(KEY)).thenAnswer { storedValue }
        whenever { keychain.upsertString(eq(KEY), any()) }.thenAnswer {
            storedValue = it.getArgument(1)
            Unit
        }
        val sut = PaykitPaymentRequestPresentationStore(keychain)
        val millisecondId = PaykitSubscriptionId("millisecond", COUNTERPARTY, "bitkit/server")
        val nanosecondId = PaykitSubscriptionId("nanosecond", COUNTERPARTY, "bitkit/server")
        val acceptedAt = mapOf(
            millisecondId to Instant.parse("2026-09-24T10:00:00.123Z"),
            nanosecondId to Instant.parse("2026-09-24T10:00:00.123456789Z"),
        )

        sut.saveSubscriptionState(IDENTITY, PaykitSubscriptionPresentationState(acceptedAt = acceptedAt))
        val backup = Json.decodeFromString<Map<String, PaykitPaymentStateBackup.Subscription>>(
            Json.encodeToString(sut.backupSnapshot()),
        )
        storedValue = null
        sut.restoreBackup(backup)

        val restoredAcceptedAt = sut.loadSubscriptionState(IDENTITY).acceptedAt
        assertEquals(acceptedAt, restoredAcceptedAt)

        fun eligiblePeriods(boundaryFraction: String) = PaykitSubscriptionRecurrence(
            every = 1,
            unit = PaykitRecurrenceUnit.Day,
            startsAt = Instant.parse("2026-09-23T10:00:00.$boundaryFraction"),
            anchor = Instant.parse("2026-09-23T10:00:00.$boundaryFraction"),
            endsAt = null,
        ).periodsThrough(
            date = Instant.parse("2026-09-24T10:00:01Z"),
            acceptedAt = restoredAcceptedAt.getValue(millisecondId),
        )

        assertEquals(1, eligiblePeriods("122999950Z").size)
        assertEquals(1, eligiblePeriods("123Z").size)
        assertEquals(2, eligiblePeriods("123000050Z").size)
    }

    @Test
    fun `invalid subscription timestamp identifies its preserved key`() = test {
        val keychain = mock<Keychain>()
        val value = """
            {"subscriptionStatesByIdentity":{"$IDENTITY":{"acceptances":[{"id":{"paymentRequestId":"subscription","counterparty":"$COUNTERPARTY","counterpartyReceiverPath":"bitkit/server"},"acceptedAt":"not-a-timestamp"}]}}}
        """.trimIndent()
        whenever(keychain.loadString(KEY)).thenReturn(value)

        val error = assertFailsWith<PaykitPaymentStateUnreadableError> {
            PaykitPaymentRequestPresentationStore(keychain).loadSubscriptionState(IDENTITY)
        }

        assertContains(error.message.orEmpty(), KEY)
        verify(keychain, never()).upsertString(eq(KEY), any())
    }
}
