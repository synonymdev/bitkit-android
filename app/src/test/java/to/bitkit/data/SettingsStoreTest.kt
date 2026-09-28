package to.bitkit.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.models.PrimaryDisplay
import to.bitkit.models.SettingsBackupV1
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Config(application = Application::class, sdk = [34])
@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest : BaseUnitTest() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sut = SettingsStore(context)

    @Before
    fun setUp() = runBlocking { sut.reset() }

    @After
    fun tearDown() = runBlocking { sut.reset() }

    @Test
    fun `balance unit switch reports only the first transition and persists its flag`() = test {
        sut.update { it.copy(selectedCurrency = "EUR") }

        val firstSwitch = sut.switchBalanceUnit()
        assertEquals(PrimaryDisplay.BITCOIN, firstSwitch?.previousDisplay)
        assertEquals(PrimaryDisplay.FIAT, firstSwitch?.newDisplay)
        assertEquals("EUR", firstSwitch?.selectedCurrency)
        assertTrue(sut.data.first().ignoresSwitchUnitToast)

        assertNull(SettingsStore(context).switchBalanceUnit())
        assertEquals(PrimaryDisplay.BITCOIN, sut.data.first().primaryDisplay)
    }

    @Test
    fun `swipe reports only the first visible to hidden transition`() = test {
        sut.update { it.copy(hideBalance = true) }

        assertFalse(sut.toggleHideBalanceFromSwipe())
        assertFalse(sut.data.first().ignoresHideBalanceToast)

        assertTrue(sut.toggleHideBalanceFromSwipe())
        assertTrue(sut.data.first().hideBalance)
        assertTrue(sut.data.first().ignoresHideBalanceToast)

        assertFalse(SettingsStore(context).toggleHideBalanceFromSwipe())
        assertFalse(sut.data.first().hideBalance)
    }

    @Test
    fun `disabled swipe does not hide the balance or consume the first toast`() = test {
        sut.update { it.copy(enableSwipeToHideBalance = false) }

        assertFalse(sut.toggleHideBalanceFromSwipe())
        assertFalse(sut.data.first().hideBalance)
        assertFalse(sut.data.first().ignoresHideBalanceToast)
    }

    @Test
    fun `restoring settings allows first gesture guidance on this device`() = test {
        val backup = SettingsBackupV1(
            createdAt = 0L,
            settings = SettingsData(
                ignoresSwitchUnitToast = true,
                ignoresHideBalanceToast = true,
            ),
        )

        assertTrue(sut.restoreFromBackup(backup).isSuccess)
        assertFalse(sut.data.first().ignoresSwitchUnitToast)
        assertFalse(sut.data.first().ignoresHideBalanceToast)
    }

    @Test
    fun `restoring settings keeps the received sheet hold armed by the restore in flight`() = test {
        // Regression: the hold is armed before the backup is read, so a wholesale settings restore used
        // to wipe it and let the replayed history raise Received sheets over the wallet.
        val restoreStartedAt = 1_700_000_000L
        sut.update { it.copy(pendingRestoreActivitySeenSince = restoreStartedAt) }
        val backup = SettingsBackupV1(createdAt = 0L, settings = SettingsData())

        assertTrue(sut.restoreFromBackup(backup).isSuccess)

        assertEquals(restoreStartedAt, sut.data.first().pendingRestoreActivitySeenSince)
        assertTrue(sut.data.first().pendingRestoreActivitySeen)
    }

    @Test
    fun `restoring settings keeps the restore tip recorded on this device`() = test {
        sut.update { it.copy(restoreSyncedBlockHeight = 900L) }
        val backup = SettingsBackupV1(createdAt = 0L, settings = SettingsData(restoreSyncedBlockHeight = 5L))

        assertTrue(sut.restoreFromBackup(backup).isSuccess)

        assertEquals(900L, sut.data.first().restoreSyncedBlockHeight)
    }
}
