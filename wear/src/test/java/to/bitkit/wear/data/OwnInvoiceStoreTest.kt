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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An [OwnInvoiceStore] in memory. */
class FakeOwnInvoiceStore : OwnInvoiceStore {
    val invoices = mutableMapOf<String, ULong>()
    var failure: Throwable? = null

    override suspend fun add(paymentHash: String, expiresAtSecs: ULong) {
        failure?.let { throw it }
        invoices[paymentHash.lowercase()] = expiresAtSecs
    }

    override suspend fun contains(paymentHash: String): Boolean = paymentHash.lowercase() in invoices
}

/** The store against the real settings file, in a temporary directory. Each [open] is a new process. */
class OwnInvoiceStoreTest : BaseUnitTest() {
    @get:Rule
    val folder = TemporaryFolder()

    private val settingsFile by lazy { File(folder.root, "datastore/settings.preferences_pb") }
    private var scope: CoroutineScope? = null
    private var now = 1_000uL

    @Test
    fun `an added invoice is known in either case until it expires, also after a restart`() = test {
        open().first.add("AB".repeat(32), expiresAtSecs = 2_000uL)
        close()
        val sut = open().first

        assertTrue(sut.contains("ab".repeat(32)))
        assertTrue(sut.contains("AB".repeat(32)))
        assertFalse(sut.contains("cd".repeat(32)))

        now = 2_000uL
        assertFalse(sut.contains("ab".repeat(32)))
        close()
    }

    @Test
    fun `adding drops the expired invoices`() = test {
        val sut = open().first
        sut.add("aa".repeat(32), expiresAtSecs = 1_500uL)
        now = 1_600uL

        sut.add("bb".repeat(32), expiresAtSecs = 3_000uL)
        now = 1_000uL

        assertFalse(sut.contains("aa".repeat(32)))
        assertTrue(sut.contains("bb".repeat(32)))
        close()
    }

    @Test
    fun `it keeps the invoices that expire last when there are too many`() = test {
        val sut = open().first
        val count = SettingsOwnInvoiceStore.MAX_INVOICES + 1

        repeat(count) { sut.add(hash(it), expiresAtSecs = 2_000uL + it.toULong()) }

        assertFalse(sut.contains(hash(0)))
        assertTrue(sut.contains(hash(1)))
        assertTrue(sut.contains(hash(count - 1)))
        close()
    }

    @Test
    fun `the wallet's wipe forgets the invoices with the settings`() = test {
        val (sut, settings) = open()
        sut.add("ab".repeat(32), expiresAtSecs = 2_000uL)

        settings.finishWipe()

        assertFalse(sut.contains("ab".repeat(32)))
        close()
    }

    private fun hash(index: Int) = index.toString(radix = 16).padStart(64, '0')

    private fun open(): Pair<SettingsOwnInvoiceStore, SettingsStore> {
        val opened = CoroutineScope(testDispatcher + Job()).also { scope = it }
        val dataStore = settingsDataStore(opened) { settingsFile }
        val settings = SettingsStore(dataStore, WipeMarker(lazy { File(folder.root, "seed") }))
        return SettingsOwnInvoiceStore(dataStore) { now } to settings
    }

    private suspend fun close() {
        scope?.coroutineContext?.job?.cancelAndJoin()
        scope = null
    }
}
