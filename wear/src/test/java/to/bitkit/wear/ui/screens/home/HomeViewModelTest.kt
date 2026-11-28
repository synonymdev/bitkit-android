package to.bitkit.wear.ui.screens.home

import app.cash.turbine.test
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.wear.data.AmbientMonitor
import to.bitkit.wear.data.NfcState
import to.bitkit.wear.data.SettingsStore
import to.bitkit.wear.data.WatchSettings
import to.bitkit.wear.models.BitcoinDisplayUnit
import to.bitkit.wear.models.FxRate
import to.bitkit.wear.models.rate
import to.bitkit.wear.repositories.CurrencyRepo
import to.bitkit.wear.repositories.SettingsSyncRepo
import to.bitkit.wear.repositories.WalletRepo
import to.bitkit.wear.repositories.WalletState
import to.bitkit.wear.repositories.WalletStatus
import to.bitkit.wear.security.FakeForegroundMonitor
import to.bitkit.wear.security.FakeNetworkMonitor
import to.bitkit.wear.security.FakeNfcMonitor
import to.bitkit.wear.security.SeedStoreError
import to.bitkit.wear.test.BaseUnitTest
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class HomeViewModelTest : BaseUnitTest() {
    private val walletRepo = mock<WalletRepo>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val walletState = MutableStateFlow(WalletState(WalletStatus.Ready, 21_000uL, hasSynced = true))
    private val rates = MutableStateFlow(emptyList<FxRate>())
    private val nfcMonitor = FakeNfcMonitor()
    private val networkMonitor = FakeNetworkMonitor()
    private val foregroundMonitor = FakeForegroundMonitor()
    private val settingsSyncRepo = mock<SettingsSyncRepo>()
    private val ambientMonitor = AmbientMonitor()

    private lateinit var sut: HomeViewModel

    @Before
    fun setUp() {
        whenever(walletRepo.walletState).thenReturn(walletState)
        whenever(currencyRepo.rates).thenReturn(rates)
        whenever(settingsStore.data).thenReturn(flowOf(WatchSettings()))
        whenever { walletRepo.start() }.thenReturn(Result.success(Unit))
        whenever { currencyRepo.refresh() }.thenReturn(Result.success(Unit))
        whenever { currencyRepo.togglePrimaryDisplay() }.thenReturn(Result.success(Unit))
        whenever { settingsSyncRepo.sync() }.thenReturn(Result.success(Unit))
        sut = HomeViewModel(
            walletRepo,
            currencyRepo,
            settingsStore,
            nfcMonitor,
            networkMonitor,
            foregroundMonitor,
            settingsSyncRepo,
            ambientMonitor,
        )
    }

    override fun onTestEnd() = foregroundMonitor.isForeground.update { false }

    @Test
    fun `rates are fetched once when home opens and again each time the wallet becomes ready`() = test {
        verify(currencyRepo, times(1)).refresh()

        walletState.update { it.copy(status = WalletStatus.Offline) }
        verify(currencyRepo, times(1)).refresh()

        walletState.update { it.copy(status = WalletStatus.Ready) }
        verify(currencyRepo, times(2)).refresh()
    }

    @Test
    fun `the phone's settings are imported when the wallet is ready, on each return and every five minutes`() = test {
        verify(settingsSyncRepo, times(1)).sync()

        testScheduler.advanceTimeBy(HomeViewModel.SETTINGS_SYNC_INTERVAL + 1.seconds)
        verify(settingsSyncRepo, times(2)).sync()

        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(HomeViewModel.SETTINGS_SYNC_INTERVAL * 3)
        verify(settingsSyncRepo, times(2)).sync()

        foregroundMonitor.isForeground.update { true }
        verify(settingsSyncRepo, times(3)).sync()

        walletState.update { it.copy(status = WalletStatus.Paused) }
        testScheduler.advanceTimeBy(HomeViewModel.SETTINGS_SYNC_INTERVAL * 3)
        verify(settingsSyncRepo, times(3)).sync()

        walletState.update { it.copy(status = WalletStatus.Ready) }
        verify(settingsSyncRepo, times(4)).sync()
    }

    @Test
    fun `the phone's settings are not imported in ambient mode, and again once the watch wakes`() = test {
        ambientMonitor.set(true)
        testScheduler.advanceTimeBy(HomeViewModel.SETTINGS_SYNC_INTERVAL * 3)
        verify(settingsSyncRepo, times(1)).sync()

        ambientMonitor.set(false)
        verify(settingsSyncRepo, times(2)).sync()
    }

    @Test
    fun `the phone's settings are not imported before the wallet is ready`() = test {
        for (status in WalletStatus.entries - WalletStatus.Ready) {
            walletState.update { it.copy(status = status) }
        }
        testScheduler.advanceTimeBy(HomeViewModel.SETTINGS_SYNC_INTERVAL * 3)

        verify(settingsSyncRepo, times(1)).sync()
    }

    @Test
    fun `the balance is written in the phone's bitcoin unit`() {
        val state = homeUiState(
            WalletState(WalletStatus.Ready, 50_000uL, hasSynced = true),
            rates = emptyList(),
            settings = WatchSettings(displayUnit = BitcoinDisplayUnit.CLASSIC),
            locale = Locale.US,
        )

        assertEquals(DisplayAmount("₿", "0.00050000"), state.primaryAmount)
    }

    @Test
    fun `rates are fetched every five minutes only while the app is on screen`() = test {
        testScheduler.advanceTimeBy(HomeViewModel.RATES_REFRESH_INTERVAL + 1.seconds)
        verify(currencyRepo, times(2)).refresh()

        foregroundMonitor.isForeground.update { false }
        testScheduler.advanceTimeBy(HomeViewModel.RATES_REFRESH_INTERVAL * 3)
        verify(currencyRepo, times(2)).refresh()

        foregroundMonitor.isForeground.update { true }
        verify(currencyRepo, times(3)).refresh()
    }

    @Test
    fun `the network returning fetches the rates only while there are none`() = test {
        networkMonitor.isOnline.update { false }
        networkMonitor.isOnline.update { true }
        verify(currencyRepo, times(2)).refresh()

        rates.update { listOf(rate("100000")) }
        networkMonitor.isOnline.update { false }
        networkMonitor.isOnline.update { true }
        verify(currencyRepo, times(2)).refresh()
    }

    @Test
    fun `a retry starts the wallet and fetches the rates`() = test {
        sut.onRetryClick()

        verify(walletRepo, times(2)).start()
        verify(currencyRepo, times(2)).refresh()
    }

    @Test
    fun `the nfc state reaches the screen`() = test {
        sut.uiState.test {
            assertEquals(NfcState.On, expectMostRecentItem().nfc)

            nfcMonitor.state.update { NfcState.Off }

            assertEquals(NfcState.Off, expectMostRecentItem().nfc)
        }
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
