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
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CurrencyRepoTest : BaseUnitTest() {
    private val settingsStore = mock<SettingsStore>()
    private val sut by lazy { CurrencyRepo(testDispatcher, mock<RatesHttpClient>(), settingsStore) }

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
