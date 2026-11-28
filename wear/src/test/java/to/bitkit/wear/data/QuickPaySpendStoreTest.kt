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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A [QuickPaySpendStore] in memory, counting as the real one does. */
class FakeQuickPaySpendStore : QuickPaySpendStore {
    var day: String? = null
    var spentCents = 0L
    var failure: Throwable? = null

    override suspend fun reserve(dayKey: String, cents: Long, capCents: Long): String? {
        failure?.let { throw it }
        if (cents <= 0 || capCents <= 0) return null
        val isNewDay = day.let { it == null || dayKey > it }
        val spent = if (isNewDay) 0L else spentCents
        if (cents > capCents - spent) return null
        if (isNewDay) day = dayKey
        spentCents = spent + cents
        return day
    }

    override suspend fun release(dayKey: String, cents: Long) {
        failure?.let { throw it }
        if (dayKey == day) spentCents = (spentCents - cents).coerceAtLeast(0L)
    }
}

/** The store against the real settings file, in a temporary directory. Each [open] is a new process. */
class QuickPaySpendStoreTest : BaseUnitTest() {
    private companion object {
        const val TODAY = "2026-11-28"
        const val TOMORROW = "2026-11-29"
    }

    @get:Rule
    val folder = TemporaryFolder()

    private val settingsFile by lazy { File(folder.root, "datastore/settings.preferences_pb") }
    private var scope: CoroutineScope? = null

    @Test
    fun `a day takes payments up to its cap and not a cent more, also after a restart`() = test {
        val sut = open().first
        assertNotNull(sut.reserve(TODAY, cents = 400, capCents = 1_000))
        assertNotNull(sut.reserve(TODAY, cents = 500, capCents = 1_000))
        close()
        val next = open().first

        assertNull(next.reserve(TODAY, cents = 101, capCents = 1_000))
        assertNotNull(next.reserve(TODAY, cents = 100, capCents = 1_000))
        assertNull(next.reserve(TODAY, cents = 1, capCents = 1_000))
        close()
    }

    @Test
    fun `a refused payment counts for nothing`() = test {
        val sut = open().first

        assertNull(sut.reserve(TODAY, cents = 1_001, capCents = 1_000))

        assertNotNull(sut.reserve(TODAY, cents = 1_000, capCents = 1_000))
        close()
    }

    @Test
    fun `a new day starts from nothing and an earlier day does not`() = test {
        val sut = open().first
        assertNotNull(sut.reserve(TOMORROW, cents = 1_000, capCents = 1_000))

        assertNull(sut.reserve(TODAY, cents = 1, capCents = 1_000))
        assertNull(sut.reserve(TOMORROW, cents = 1, capCents = 1_000))

        assertNotNull(sut.reserve("2026-11-30", cents = 1_000, capCents = 1_000))
        close()
    }

    @Test
    fun `a payment of an earlier day is counted under the day last counted`() = test {
        val sut = open().first
        assertEquals(TOMORROW, sut.reserve(TOMORROW, cents = 400, capCents = 1_000))

        assertEquals(TOMORROW, sut.reserve(TODAY, cents = 600, capCents = 1_000))
        assertNull(sut.reserve(TOMORROW, cents = 1, capCents = 1_000))
        close()
    }

    @Test
    fun `a released payment frees its cents in the day counted, and nothing in another day`() = test {
        val sut = open().first
        assertNotNull(sut.reserve(TODAY, cents = 1_000, capCents = 1_000))

        sut.release(TOMORROW, cents = 1_000)
        sut.release(TODAY, cents = 0)
        assertNull(sut.reserve(TODAY, cents = 1, capCents = 1_000))

        sut.release(TODAY, cents = 400)
        assertNull(sut.reserve(TODAY, cents = 401, capCents = 1_000))
        assertNotNull(sut.reserve(TODAY, cents = 400, capCents = 1_000))

        sut.release(TODAY, cents = 5_000)
        assertNotNull(sut.reserve(TODAY, cents = 1_000, capCents = 1_000))
        close()
    }

    @Test
    fun `a lowered cap counts what was already spent`() = test {
        val sut = open().first
        assertNotNull(sut.reserve(TODAY, cents = 800, capCents = 1_000))

        assertNull(sut.reserve(TODAY, cents = 1, capCents = 500))
        close()
    }

    @Test
    fun `nothing and less than nothing are refused`() = test {
        val sut = open().first

        assertNull(sut.reserve(TODAY, cents = 0, capCents = 1_000))
        assertNull(sut.reserve(TODAY, cents = -5, capCents = 1_000))
        assertNull(sut.reserve(TODAY, cents = 1, capCents = 0))
        assertNull(sut.reserve(TODAY, cents = Long.MAX_VALUE, capCents = 1_000))
        close()
    }

    @Test
    fun `the wallet's wipe forgets the day's spend with the settings`() = test {
        val (sut, settings) = open()
        assertNotNull(sut.reserve(TODAY, cents = 1_000, capCents = 1_000))

        settings.finishWipe()

        assertNotNull(sut.reserve(TODAY, cents = 1_000, capCents = 1_000))
        close()
    }

    private fun open(): Pair<SettingsQuickPaySpendStore, SettingsStore> {
        val opened = CoroutineScope(testDispatcher + Job()).also { scope = it }
        val dataStore = settingsDataStore(opened) { settingsFile }
        val settings = SettingsStore(dataStore, WipeMarker(lazy { File(folder.root, "seed") }))
        return SettingsQuickPaySpendStore(dataStore) to settings
    }

    private suspend fun close() {
        scope?.coroutineContext?.job?.cancelAndJoin()
        scope = null
    }
}
