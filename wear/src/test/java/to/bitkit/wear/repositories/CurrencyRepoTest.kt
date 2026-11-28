package to.bitkit.wear.repositories

import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.RatesHttpClient
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.FxRateResponse
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.models.rate
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.test.BaseUnitTest
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class CurrencyRepoTest : BaseUnitTest() {
    private val settingsStore = mock<SettingsStore>()
    private val ratesHttpClient = mock<RatesHttpClient>()
    private var elapsedMs = 5_000L
    private val sut by lazy { CurrencyRepo(testDispatcher, ratesHttpClient, settingsStore) { elapsedMs } }

    @Test
    fun `the rates' age counts from their fetch on the clock that runs through sleep`() = test {
        whenever(ratesHttpClient.fetchLatestRates()).thenReturn(FxRateResponse(listOf(rate("100000"))))
        assertNull(sut.ratesAge())

        assertTrue(sut.refresh().isSuccess)
        assertEquals(Duration.ZERO, sut.ratesAge())

        elapsedMs += 3.hours.inWholeMilliseconds
        assertEquals(3.hours, sut.ratesAge())
    }

    @Test
    fun `a fetch that fails leaves the rates' age as it was`() = test {
        whenever(ratesHttpClient.fetchLatestRates()).thenAnswer { throw IOException("offline") }

        assertTrue(sut.refresh().isFailure)

        assertNull(sut.ratesAge())
    }

    @Test
    fun `switching the unit stores fiat first when bitcoin was first, and back`() = test {
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings(primaryDisplay = PrimaryDisplay.BITCOIN)))
        assertTrue(sut.togglePrimaryDisplay().isSuccess)
        verify(settingsStore).setPrimaryDisplay(PrimaryDisplay.FIAT)

        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings(primaryDisplay = PrimaryDisplay.FIAT)))
        assertTrue(sut.togglePrimaryDisplay().isSuccess)
        verify(settingsStore).setPrimaryDisplay(PrimaryDisplay.BITCOIN)
    }

    @Test
    fun `a unit switch that cannot be stored is reported`() = test {
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings()))
        whenever(settingsStore.setPrimaryDisplay(any())).thenAnswer { throw SeedStoreError.Failed() }

        assertIs<SeedStoreError.Failed>(sut.togglePrimaryDisplay().exceptionOrNull())
    }
}
