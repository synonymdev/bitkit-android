package to.bitkit.repositories

import kotlinx.serialization.SerializationException
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PaykitAllowanceStoreTest : BaseUnitTest() {
    private val identity = PaykitAllowanceFixtures.identityKey
    private val keychain = mock<Keychain>()
    private var stored: String? = null
    private lateinit var sut: PaykitAllowanceStore

    @Before
    fun setUp() = test {
        whenever(keychain.loadString(any())).thenAnswer { stored }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer { stored = it.getArgument(1) }
        sut = PaykitAllowanceStore(keychain)
    }

    @Test
    fun `only a change to backed-up state asks for a backup`() = test {
        sut.save(identity, PaykitAllowanceLocalState(lastTrustedTimeMillis = 1L))
        val afterFirstSave = sut.backupStateVersion.value

        sut.save(identity, PaykitAllowanceLocalState(lastTrustedTimeMillis = 2L))
        assertEquals(afterFirstSave, sut.backupStateVersion.value, "A new trusted-time watermark alone asks for none")

        sut.save(identity, PaykitAllowanceLocalState(notifiedRequestIds = setOf("request-1")))
        assertEquals(afterFirstSave + 1, sut.backupStateVersion.value)
    }

    @Test
    fun `a backup snapshot of unreadable state fails instead of backing up nothing`() = test {
        stored = "{not json"

        assertFailsWith<SerializationException> { sut.backupSnapshot() }
        assertEquals(PaykitAllowanceLocalState(), sut.load(identity), "Ordinary reads still fall back to empty")
    }

    @Test
    fun `restore replaces each restored identity's state`() = test {
        val other = PaykitAllowanceFixtures.counterpartyKey
        sut.save(identity, PaykitAllowanceLocalState(notifiedRequestIds = setOf("old")))
        sut.save(other, PaykitAllowanceLocalState(notifiedRequestIds = setOf("kept")))
        val restored = PaykitAllowanceLocalState(autoPaidSatsByAllowance = mapOf("key" to 1_000uL))
        val before = sut.backupStateVersion.value

        sut.restoreBackup(mapOf(identity to restored))

        assertEquals(restored, sut.load(identity))
        assertEquals(setOf("kept"), sut.load(other).notifiedRequestIds)
        assertEquals(before + 1, sut.backupStateVersion.value)
        assertEquals(2, sut.backupSnapshot().size)
    }

    @Test
    fun `state saved before the automatic payment checks still loads`() = test {
        val key = PubkyPublicKeyFormat.normalized(identity) ?: identity
        stored = """{"statesByIdentity":{"$key":{"notifiedRequestIds":["request-1"]}}}"""

        val state = sut.load(identity)

        assertEquals(setOf("request-1"), state.notifiedRequestIds)
        assertEquals(null, state.ledgerRevisionFloor)
        assertEquals(emptyList(), state.automaticSpends)
        assertEquals(emptySet(), state.endedAllowanceIds)
    }

    @Test
    fun `restore never lowers the automatic payment checks already stored`() = test {
        val spend = PaykitAllowanceLocalState.AutomaticSpend("attempt-1", "contact", "allowance-1", 1_000uL, 1L)
        val restoredSpend = spend.copy(attemptId = "attempt-2")
        sut.save(
            identity,
            PaykitAllowanceLocalState(
                ledgerRevisionFloor = 9uL,
                automaticSpends = listOf(spend),
                endedAllowanceIds = setOf("contact/allowance-1"),
            ),
        )

        sut.restoreBackup(
            mapOf(
                identity to PaykitAllowanceLocalState(
                    ledgerRevisionFloor = 4uL,
                    automaticSpends = listOf(restoredSpend),
                    endedAllowanceIds = setOf("contact/allowance-2"),
                ),
            ),
        )

        val restored = sut.load(identity)
        assertEquals(9uL, restored.ledgerRevisionFloor)
        assertEquals(setOf(spend, restoredSpend), restored.automaticSpends.toSet())
        assertEquals(setOf("contact/allowance-1", "contact/allowance-2"), restored.endedAllowanceIds)
    }
}
