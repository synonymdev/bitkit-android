package to.bitkit.wear.ui.screens.home

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.models.PrimaryDisplay
import to.bitkit.wear.repositories.CurrencyRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.test.BaseUnitTest

class HomeViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val settingsStore = mock<SettingsStore>()

    private fun createSut(display: PrimaryDisplay): HomeViewModel {
        whenever(walletRepo.walletState).thenReturn(MutableStateFlow(WalletState()))
        whenever(currencyRepo.rates).thenReturn(MutableStateFlow(emptyList<FxRate>()))
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings(primaryDisplay = display)))
        return HomeViewModel(walletRepo, currencyRepo, settingsStore)
    }

    @Test
    fun `tapping the balance stores fiat first when bitcoin was first`() = test {
        whenever(walletRepo.start()).thenReturn(Result.success(Unit))
        whenever(currencyRepo.refresh()).thenReturn(Result.success(Unit))
        val sut = createSut(PrimaryDisplay.BITCOIN)

        sut.onUnitToggle()

        verify(settingsStore).setPrimaryDisplay(PrimaryDisplay.FIAT)
    }

    @Test
    fun `tapping the balance stores bitcoin first when fiat was first`() = test {
        whenever(walletRepo.start()).thenReturn(Result.success(Unit))
        whenever(currencyRepo.refresh()).thenReturn(Result.success(Unit))
        val sut = createSut(PrimaryDisplay.FIAT)

        sut.onUnitToggle()

        verify(settingsStore).setPrimaryDisplay(PrimaryDisplay.BITCOIN)
    }
}
