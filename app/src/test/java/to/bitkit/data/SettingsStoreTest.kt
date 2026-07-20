package to.bitkit.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import to.bitkit.models.SettingsBackupV1
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStoreTest : BaseUnitTest() {

    @Test
    fun `contact payments remain off while a transition is pending`() {
        val settings = SettingsData(
            sharesPublicPaykitEndpoints = true,
            sharesPrivatePaykitEndpoints = true,
            pendingContactPaymentsEnabled = true,
        )

        assertFalse(settings.areContactPaymentsEnabled())
    }

    @Test
    fun `restore enables Paykit payment methods missing from an old backup`() = test {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sut = SettingsStore(context)
        sut.reset()
        val backup = SettingsBackupV1(
            createdAt = 0L,
            settings = SettingsData(
                publicPaykitLightningEnabled = false,
                publicPaykitOnchainEnabled = false,
            ),
        )

        val result = sut.restoreFromBackup(backup)
        val restored = sut.data.first()

        assertTrue(result.isSuccess)
        assertTrue(restored.publicPaykitLightningEnabled)
        assertTrue(restored.publicPaykitOnchainEnabled)
        assertFalse(restored.isPinEnabled)
    }

    @Test
    fun `restore converts an interrupted enable into a pending disable`() = test {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sut = SettingsStore(context)
        sut.reset()
        val backup = SettingsBackupV1(
            createdAt = 0L,
            settings = SettingsData(
                sharesPublicPaykitEndpoints = true,
                sharesPrivatePaykitEndpoints = true,
                pendingContactPaymentsEnabled = true,
            ),
        )

        val result = sut.restoreFromBackup(backup)
        val restored = sut.data.first()

        assertTrue(result.isSuccess)
        assertFalse(restored.pendingContactPaymentsEnabled ?: true)
    }
}
