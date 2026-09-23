package to.bitkit.services.offline

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import to.bitkit.models.OfflineReceiveDevSettings
import to.bitkit.services.OfflineReceiveRequest
import to.bitkit.services.OfflineReceiveService
import to.bitkit.services.OfflineReceiveUnavailable
import to.bitkit.services.PreparedOfflineInvoice
import to.bitkit.services.UnavailableOfflineReceiveService
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GatedOfflineReceiveServiceTest : BaseUnitTest() {
    private val request = OfflineReceiveRequest("request", 1_000uL, "Dinner")
    private val native = RecordingOfflineReceiveService()
    private val devSettings = MutableStateFlow(OfflineReceiveDevSettings())

    private fun sut(nativeServices: Set<OfflineReceiveService>, isNativeAvailable: Boolean) =
        GatedOfflineReceiveService(
            nativeServices = nativeServices,
            unavailable = UnavailableOfflineReceiveService(),
            settingsSource = OfflineReceiveSettingsSource(devSettings, isNativeAvailable),
        )

    @Test
    fun `without a native provider the feature stays unavailable even when toggled on`() = test {
        devSettings.value = OfflineReceiveDevSettings(isEnabled = true)
        val sut = sut(emptySet(), isNativeAvailable = false)

        assertFalse(sut.canReceive(1_000uL).getOrThrow())
        assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())
    }

    @Test
    fun `native provider is ignored while the dev toggle is off`() = test {
        val sut = sut(setOf(native), isNativeAvailable = true)

        assertFalse(sut.canReceive(1_000uL).getOrThrow())
        assertIs<OfflineReceiveUnavailable>(sut.prepareInvoice(request).exceptionOrNull())
        assertEquals(0, native.calls)
    }

    @Test
    fun `native provider is used only when compiled in and toggled on`() = test {
        devSettings.value = OfflineReceiveDevSettings(isEnabled = true)
        assertFalse(sut(setOf(native), isNativeAvailable = false).canReceive(1_000uL).getOrThrow())
        assertEquals(0, native.calls)

        val sut = sut(setOf(native), isNativeAvailable = true)
        assertTrue(sut.canReceive(1_000uL).getOrThrow())
        assertEquals(native.invoice, sut.prepareInvoice(request).getOrThrow())
        assertEquals(2, native.calls)
    }

    private class RecordingOfflineReceiveService : OfflineReceiveService {
        var calls = 0
        val invoice = PreparedOfflineInvoice("bolt11", 1_000uL, "Dinner", 2_000_003_600_000L, "hash")

        override suspend fun canReceive(amountSats: ULong): Result<Boolean> {
            calls++
            return Result.success(true)
        }

        override suspend fun prepareInvoice(request: OfflineReceiveRequest): Result<PreparedOfflineInvoice> {
            calls++
            return Result.success(invoice)
        }
    }
}
