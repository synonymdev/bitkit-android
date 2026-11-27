package to.bitkit.wear.ui.screens.home

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.repositories.CurrencyRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val walletState = MutableStateFlow(WalletState(WalletStatus.Ready, 21_000uL, hasSynced = true))

    private lateinit var sut: HomeViewModel

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(currencyRepo.rates).thenReturn(MutableStateFlow(emptyList<FxRate>()))
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings()))
        whenever { walletRepo.start() }.thenReturn(Result.success(Unit))
        whenever { currencyRepo.refresh() }.thenReturn(Result.success(Unit))
        whenever { currencyRepo.togglePrimaryDisplay() }.thenReturn(Result.success(Unit))
        sut = HomeViewModel(walletRepo, currencyRepo, settingsStore)
    }

    @Test
    fun `tapping the balance switches the unit through the repo`() = test {
        sut.onUnitToggle()

        verify(currencyRepo).togglePrimaryDisplay()
    }

    @Test
    fun `a unit switch that fails leaves the screen as it was`() = test {
        whenever(currencyRepo.togglePrimaryDisplay()).thenReturn(Result.failure(SeedStoreError.Failed()))

        sut.uiState.test {
            val before = expectMostRecentItem()
            sut.onUnitToggle()
            expectNoEvents()
            assertTrue(before.isBitcoinPrimary)
        }
    }

    @Test
    fun `a removed wallet shows as missing to home with no settings page collecting`() = test {
        sut.uiState.test {
            assertFalse(expectMostRecentItem().isMissing)

            walletState.update { WalletState(WalletStatus.Missing) }

            assertTrue(expectMostRecentItem().isMissing)
        }
    }

    @Test
    fun `a wallet deleted for a lost key shows as removed`() = test {
        sut.uiState.test {
            walletState.update { WalletState(WalletStatus.Removed) }

            val state = expectMostRecentItem()
            assertTrue(state.isRemoved)
            assertFalse(state.isMissing)
        }
    }

    @Test
    fun `a wallet paused in the background shows as loading with its last balance`() = test {
        sut.uiState.test {
            walletState.update { it.copy(status = WalletStatus.Paused) }

            val state = expectMostRecentItem()
            assertTrue(state.isLoading)
            assertTrue(state.primaryAmount != null)
        }
    }
}
