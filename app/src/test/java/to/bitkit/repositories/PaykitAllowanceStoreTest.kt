package to.bitkit.repositories

import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.data.keychain.Keychain
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals

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
}
