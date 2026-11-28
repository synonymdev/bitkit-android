package to.bitkit.wear.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.bitkit.wear.test.BaseUnitTest
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An [UncertainSendStore] in memory. */
class FakeUncertainSendStore : UncertainSendStore {
    val sends = mutableMapOf<String, UncertainSend>()
    var failure: Throwable? = null

    override suspend fun begin(send: UncertainSend): Boolean {
        failure?.let { throw it }
        return sends.put(send.key, send.copy(sparkPayee = send.sparkPayee?.lowercase())) != null
    }

    override suspend fun settle(key: String) {
        failure?.let { throw it }
        sends.remove(key)
    }

    override suspend fun transferKeys(sparkPayee: String): List<String> {
        failure?.let { throw it }
        return sends.values.filter { it.sparkPayee == sparkPayee.lowercase() }.map { it.key }.reversed()
    }
}

/** The store against the real settings file, in a temporary directory. Each [open] is a new process. */
class UncertainSendStoreTest : BaseUnitTest() {
    private companion object {
        const val PAYEE = "03a1b2c3d4e5f60718293a4b5c6d7e8f9001122334455667788990aabbccddeeff"
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val settingsFile by lazy { File(folder.root, "datastore/settings.preferences_pb") }
    private var scope: CoroutineScope? = null
    private var now = 1_000uL

    @Test
    fun `a noted transfer keeps its key for its payee in either case, also after a restart`() = test {
        assertFalse(open().first.begin(UncertainSend("key-a", sparkPayee = PAYEE)))
        close()
        val sut = open().first

        assertEquals(listOf("key-a"), sut.transferKeys(PAYEE))
        assertEquals(listOf("key-a"), sut.transferKeys(PAYEE.uppercase()))
        assertTrue(sut.transferKeys("02other").isEmpty())
        close()
    }

    @Test
    fun `every transfer noted for one payee is told, the newest first`() = test {
        val sut = open().first
        sut.begin(UncertainSend("key-a", sparkPayee = PAYEE))
        now += 10uL
        sut.begin(UncertainSend("key-b", sparkPayee = PAYEE.uppercase()))

        assertEquals(listOf("key-b", "key-a"), sut.transferKeys(PAYEE))
        close()
    }

    @Test
    fun `noting a send again tells that an earlier attempt used its key`() = test {
        val sut = open().first
        val send = UncertainSend("key-0", expiresAtSecs = 2_000uL)

        assertFalse(sut.begin(send))
        close()

        assertTrue(open().first.begin(send))
        close()
    }

    @Test
    fun `a settled send is forgotten`() = test {
        val sut = open().first
        sut.begin(UncertainSend("key-a", sparkPayee = PAYEE))
        sut.begin(UncertainSend("key-0", expiresAtSecs = 2_000uL))

        sut.settle("key-a")
        sut.settle("key-0")
        sut.settle("unknown")

        assertTrue(sut.transferKeys(PAYEE).isEmpty())
        assertFalse(sut.begin(UncertainSend("key-0", expiresAtSecs = 2_000uL)))
        close()
    }

    @Test
    fun `an invoice's send is kept for a day after the invoice expired, and a transfer for good`() = test {
        val sut = open().first
        sut.begin(UncertainSend("key-0", expiresAtSecs = 2_000uL))
        sut.begin(UncertainSend("key-a", sparkPayee = PAYEE))

        now = 2_000uL + SettingsUncertainSendStore.EXPIRED_KEPT_SECS - 1uL
        sut.begin(UncertainSend("other-1", expiresAtSecs = now + 10uL))
        assertTrue(sut.begin(UncertainSend("key-0", expiresAtSecs = 2_000uL)))

        now = 2_000uL + SettingsUncertainSendStore.EXPIRED_KEPT_SECS
        sut.begin(UncertainSend("other-2", expiresAtSecs = now + 10uL))
        assertFalse(sut.begin(UncertainSend("key-0", expiresAtSecs = 2_000uL)))
        assertEquals(listOf("key-a"), sut.transferKeys(PAYEE))
        close()
    }

    @Test
    fun `it keeps the newest sends when there are too many`() = test {
        val sut = open().first
        val count = SettingsUncertainSendStore.MAX_SENDS + 1

        repeat(count) {
            now = 1_000uL + it.toULong()
            sut.begin(UncertainSend("key-$it", sparkPayee = "02payee$it"))
        }

        assertTrue(sut.transferKeys("02payee0").isEmpty())
        assertEquals(listOf("key-1"), sut.transferKeys("02payee1"))
        assertEquals(listOf("key-${count - 1}"), sut.transferKeys("02payee${count - 1}"))
        close()
    }

    @Test
    fun `the wallet's wipe forgets the sends with the settings`() = test {
        val (sut, settings) = open()
        sut.begin(UncertainSend("key-a", sparkPayee = PAYEE))

        settings.finishWipe()

        assertTrue(sut.transferKeys(PAYEE).isEmpty())
        close()
    }

    private fun open(): Pair<SettingsUncertainSendStore, SettingsStore> {
        val opened = CoroutineScope(testDispatcher + Job()).also { scope = it }
        val dataStore = settingsDataStore(opened) { settingsFile }
        val settings = SettingsStore(dataStore, WipeMarker(lazy { File(folder.root, "seed") }))
        return SettingsUncertainSendStore(dataStore) { now } to settings
    }

    private suspend fun close() {
        scope?.coroutineContext?.job?.cancelAndJoin()
        scope = null
    }
}
