package to.bitkit.wear.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.security.FakeDeviceLock
import to.bitkit.wear.security.FakeSeedKey
import to.bitkit.wear.security.KeystoreSeedStore
import to.bitkit.wear.security.WalletSeed
import to.bitkit.wear.test.BaseUnitTest
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wipe note against the real settings file and the real marker file, in a temporary directory. Each [open] is
 * a new process over what is on disk.
 */
class SettingsStoreTest : BaseUnitTest() {
    @get:Rule
    val folder = TemporaryFolder()

    private val settingsFile by lazy { File(folder.root, "datastore/settings.preferences_pb") }
    private val seedDirectory by lazy { File(folder.root, "seed") }
    private val markerFile by lazy { File(seedDirectory, "wipe.pending") }

    @Test
    fun `a wipe note is an empty file beside the seed, and finishing the wipe deletes it`() = test {
        val sut = open()
        assertFalse(sut.isWipePending())

        sut.beginWipe(showsRemovedNotice = false)

        assertTrue(sut.isWipePending())
        assertEquals(0, markerFile.length())
        assertFalse(sut.isRemovedNoticePending())

        sut.finishWipe()

        assertFalse(sut.isWipePending())
        assertFalse(markerFile.exists())
        close()
    }

    @Test
    fun `a wipe note survives the process and a corrupted settings file`() = test {
        open().beginWipe(showsRemovedNotice = false)
        close()
        settingsFile.parentFile?.mkdirs()
        settingsFile.writeBytes(byteArrayOf(-1, -1, -1, -1))

        val next = open()

        assertTrue(next.isWipePending())
        assertTrue(markerFile.exists())
        close()
    }

    @Test
    fun `a corrupted settings file is reset and never starts a wipe`() = test {
        open().setPrimaryDisplay(PrimaryDisplay.FIAT)
        close()
        settingsFile.writeBytes(byteArrayOf(-1, -1, -1, -1))

        val next = open()

        assertFalse(next.isWipePending())
        assertFalse(markerFile.exists())
        assertFalse(next.isRemovedNoticePending())
        assertEquals(WatchSettings(), next.data.first())
        close()
    }

    @Test
    fun `a wipe note survives the seed being deleted`() = test {
        val seedStore = KeystoreSeedStore(lazy { seedDirectory }, FakeSeedKey(), FakeDeviceLock(), false)
        seedStore.save(WalletSeed(mnemonic = "words", passphrase = null)).getOrThrow()
        val sut = open()
        sut.beginWipe(showsRemovedNotice = false)

        seedStore.wipe().getOrThrow()

        assertFalse(seedStore.exists())
        assertTrue(sut.isWipePending())
        close()
    }

    @Test
    fun `a lost key wipe leaves its notice, which outlives the wipe`() = test {
        val sut = open()

        sut.beginWipe(showsRemovedNotice = true)
        sut.finishWipe()

        assertFalse(sut.isWipePending())
        assertTrue(sut.isRemovedNoticePending())
        close()
    }

    @Test
    fun `a wipe note that cannot be synced is deleted again, so no wipe is pending`() = test {
        var isWritten = false
        val sut = open { isWritten = markerFile.exists() }

        assertFailsWith<IOException> { sut.beginWipe(showsRemovedNotice = true) }

        assertTrue(isWritten)
        assertFalse(sut.isWipePending())
        assertFalse(markerFile.exists())
        assertFalse(sut.isRemovedNoticePending())
        close()
    }

    @Test
    fun `a wipe note written again that cannot be synced stays`() = test {
        open().beginWipe(showsRemovedNotice = false)
        close()
        val sut = open {}

        assertFailsWith<IOException> { sut.beginWipe(showsRemovedNotice = false) }

        assertTrue(sut.isWipePending())
        close()
    }

    private var scope: CoroutineScope? = null

    private fun scope() = CoroutineScope(testDispatcher + Job())

    private fun open(): SettingsStore = open(WipeMarker(lazy { seedDirectory }))

    private fun open(onSyncFailure: () -> Unit): SettingsStore = open(
        WipeMarker(lazy { seedDirectory }) {
            onSyncFailure()
            throw IOException("sync")
        },
    )

    private fun open(wipeMarker: WipeMarker): SettingsStore {
        val opened = scope().also { scope = it }
        return SettingsStore(settingsDataStore(opened) { settingsFile }, wipeMarker)
    }

    private suspend fun close() {
        scope?.coroutineContext?.job?.cancelAndJoin()
        scope = null
    }
}
