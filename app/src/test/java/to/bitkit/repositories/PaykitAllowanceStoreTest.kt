package to.bitkit.repositories

import kotlinx.serialization.SerializationException
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.data.keychain.KeychainError
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PaykitAllowanceStoreTest : BaseUnitTest() {
    private val identity = PaykitAllowanceFixtures.identityKey
    private val keychain = mock<Keychain>()
    private val stateKey = Keychain.Key.PAYKIT_ALLOWANCE_STATE.name
    private val pauseKey = Keychain.Key.PAYKIT_ALLOWANCE_RESTORE_PAUSE.name
    private val values = mutableMapOf<String, String>()
    private var stored: String?
        get() = values[stateKey]
        set(value) {
            if (value == null) values.remove(stateKey) else values[stateKey] = value
        }
    private lateinit var sut: PaykitAllowanceStore

    @Before
    fun setUp() = test {
        whenever(keychain.loadString(any())).thenAnswer { values[it.getArgument(0)] }
        whenever(keychain.upsertString(any(), any())).doSuspendableAnswer {
            values[it.getArgument(0)] = it.getArgument(1)
        }
        whenever(keychain.delete(any())).doSuspendableAnswer {
            values.remove(it.getArgument<String>(0))
            Unit
        }
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
    fun `a save never replaces state this build cannot read`() = test {
        stored = "{\"statesByIdentity\":{\"other\":{\"journal\":[{\"stage\":\"from-a-newer-build\"}]}}}"
        val unreadable = stored

        assertFailsWith<SerializationException> { sut.save(identity, PaykitAllowanceLocalState()) }
        assertEquals(unreadable, stored)
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
    fun `restore pause survives a reload until resumed`() = test {
        sut.save(identity, PaykitAllowanceLocalState(notifiedRequestIds = setOf("request-1")))

        sut.pauseAfterRestore()

        assertTrue(PaykitAllowanceStore(keychain).isPausedAfterRestore())
        assertEquals(setOf("request-1"), PaykitAllowanceStore(keychain).load(identity).notifiedRequestIds)

        sut.resumeAfterRestore()

        assertFalse(PaykitAllowanceStore(keychain).isPausedAfterRestore())
        assertEquals(setOf("request-1"), sut.load(identity).notifiedRequestIds)
    }

    @Test
    fun `restore pause is kept apart from the backed-up allowance state`() = test {
        val restored = PaykitAllowanceLocalState(autoPaidSatsByAllowance = mapOf("key" to 1_000uL))
        sut.save(identity, PaykitAllowanceLocalState(notifiedRequestIds = setOf("request-1")))
        val state = stored
        val snapshot = sut.backupSnapshot()
        val before = sut.backupStateVersion.value

        sut.pauseAfterRestore()

        assertEquals(state, stored, "The pause never rewrites the allowance state")
        assertEquals(snapshot, sut.backupSnapshot())
        assertEquals(before, sut.backupStateVersion.value)
        assertTrue(pauseKey in values)

        sut.restoreBackup(mapOf(identity to restored))

        assertTrue(sut.isPausedAfterRestore())
        assertEquals(setOf(PubkyPublicKeyFormat.normalized(identity) ?: identity), sut.backupSnapshot().keys)
    }

    @Test
    fun `restore pause never rewrites state this build cannot read`() = test {
        stored = "{\"statesByIdentity\":{\"other\":{\"journal\":[{\"stage\":\"from-a-newer-build\"}]}}}"
        val unreadable = stored

        sut.pauseAfterRestore()
        assertTrue(sut.isPausedAfterRestore())
        sut.resumeAfterRestore()

        assertFalse(sut.isPausedAfterRestore())
        assertEquals(unreadable, stored)
    }

    @Test
    fun `a restore pause that cannot be read counts as paused`() = test {
        assertFalse(sut.isPausedAfterRestore())

        whenever(keychain.loadString(pauseKey)).thenAnswer { throw KeychainError.FailedToLoad(pauseKey) }

        assertTrue(sut.isPausedAfterRestore())
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
