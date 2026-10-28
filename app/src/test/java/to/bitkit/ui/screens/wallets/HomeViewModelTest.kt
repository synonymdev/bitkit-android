package to.bitkit.ui.screens.wallets

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsData
import to.bitkit.env.Env
import to.bitkit.models.BalanceState
import to.bitkit.models.HwWallet
import to.bitkit.models.Suggestion
import to.bitkit.models.TransportType
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.CurrencyState
import to.bitkit.repositories.HwWalletRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.SuggestionsRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.UsdtRepo
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest : BaseUnitTest() {

    private val primaryUri = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798@127.0.0.1:9735"
    private val walletRepo = mock<WalletRepo>()
    private val widgetsRepo = mock<WidgetsRepo>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val transferRepo = mock<TransferRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val suggestionsRepo = mock<SuggestionsRepo>()
    private val usdtRepo = mock<UsdtRepo>()
    private val usdtState = MutableStateFlow(UsdtWalletState())

    private lateinit var hardwareWallets: MutableStateFlow<ImmutableList<HwWallet>>
    private lateinit var suggestions: MutableStateFlow<List<Suggestion>>
    private lateinit var settings: MutableStateFlow<SettingsData>
    private lateinit var balanceState: MutableStateFlow<BalanceState>

    @Before
    fun setUp() {
        hardwareWallets = MutableStateFlow(persistentListOf())
        suggestions = MutableStateFlow(emptyList())
        settings = MutableStateFlow(SettingsData())
        balanceState = MutableStateFlow(BalanceState())

        whenever(walletRepo.balanceState).thenReturn(balanceState)
        whenever(widgetsRepo.widgetsDataFlow).thenReturn(MutableStateFlow(WidgetsData()))
        whenever(widgetsRepo.articlesFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(widgetsRepo.factsFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(settingsStore.data).thenReturn(settings)
        whenever(transferRepo.activeTransfers).thenReturn(MutableStateFlow(emptyList()))
        whenever(pubkyRepo.isAuthenticated).thenReturn(MutableStateFlow(false))
        whenever(pubkyRepo.displayName).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.displayImageUri).thenReturn(MutableStateFlow(null))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(0L))
        whenever {
            activityRepo.getActivities(walletId = null, limit = 1u)
        }.thenReturn(Result.success(emptyList()))
        whenever(hwWalletRepo.wallets).thenReturn(hardwareWallets)
        whenever(suggestionsRepo.suggestionsFlow).thenReturn(suggestions)
        whenever(usdtRepo.state).thenReturn(usdtState)
    }

    @Test
    fun `updates suggestions from repo`() = test {
        suggestions.value = listOf(Suggestion.HARDWARE)
        val sut = createViewModel()

        advanceUntilIdle()

        assertTrue(Suggestion.HARDWARE in sut.uiState.value.suggestions)
    }

    @Test
    fun `shows primary setup only after settings confirm no primary is configured`() = test {
        val pendingSettings = MutableSharedFlow<SettingsData>(replay = 1)
        whenever(settingsStore.data).thenReturn(pendingSettings)
        val sut = createViewModel()

        assertFalse(sut.uiState.value.showPrimaryNodeSetup)
        pendingSettings.emit(SettingsData())
        advanceUntilIdle()

        assertTrue(sut.uiState.value.showPrimaryNodeSetup)
    }

    @Test
    fun `primary setup follows persisted configuration changes`() = test {
        val sut = createViewModel()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showPrimaryNodeSetup)

        settings.value = settings.value.copy(primaryNodeUri = primaryUri)
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showPrimaryNodeSetup)

        settings.value = settings.value.copy(primaryNodeUri = primaryUri.replace(":9735", ":9736"))
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showPrimaryNodeSetup)

        settings.value = settings.value.copy(primaryNodeUri = "")
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showPrimaryNodeSetup)
    }

    @Test
    fun `saved primary hides setup on startup with no channel or balance`() = test {
        settings.value = settings.value.copy(primaryNodeUri = primaryUri)
        val sut = createViewModel()

        advanceUntilIdle()

        assertFalse(sut.uiState.value.showPrimaryNodeSetup)

        settings.value = settings.value.copy(primaryFundingPaused = true)
        balanceState.value = BalanceState(totalOnchainSats = 25_000uL)
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showPrimaryNodeSetup)

        balanceState.value = BalanceState()
        advanceUntilIdle()
        assertFalse(sut.uiState.value.showPrimaryNodeSetup)
    }

    @Test
    fun `updates hardware wallets from repo`() = test {
        val hardwareWallet = hardwareWallet()
        hardwareWallets.value = persistentListOf(hardwareWallet)
        val sut = createViewModel()

        advanceUntilIdle()

        assertTrue(hardwareWallet in sut.uiState.value.hardwareWallets)
    }

    @Test
    fun `hides empty state for hardware wallet balance`() = test {
        hardwareWallets.value = persistentListOf(hardwareWallet(balanceSats = 1uL))
        val sut = createViewModel()

        advanceUntilIdle()

        assertFalse(sut.uiState.value.showEmptyState)
    }

    @Test
    fun `empty state follows the enabled USDT balance`() = test {
        val sut = createViewModel()
        advanceUntilIdle()
        assertTrue(sut.uiState.value.showEmptyState)

        usdtState.value = UsdtWalletState(balance = 50_000uL)
        advanceUntilIdle()
        assertEquals(!Env.isUsdtEnabled, sut.uiState.value.showEmptyState)
    }

    @Test
    fun `onPullToRefresh refreshes rates and widgets`() = test {
        val sut = createViewModel()

        sut.onPullToRefresh()
        advanceUntilIdle()

        verify(currencyRepo).triggerRefresh()
        verify(widgetsRepo).refreshEnabledWidgets()
    }

    @Test
    fun `onPullToRefresh refreshes widgets while rates refresh is pending`() = test {
        val release = CompletableDeferred<Unit>()
        whenever(currencyRepo.triggerRefresh()).doSuspendableAnswer { release.await() }
        val sut = createViewModel()

        sut.onPullToRefresh()
        advanceUntilIdle()

        verify(widgetsRepo).refreshEnabledWidgets()

        release.complete(Unit)
        advanceUntilIdle()

        verify(currencyRepo).triggerRefresh()
    }

    private fun createViewModel() = HomeViewModel(
        walletRepo = walletRepo,
        widgetsRepo = widgetsRepo,
        currencyRepo = currencyRepo,
        settingsStore = settingsStore,
        transferRepo = transferRepo,
        pubkyRepo = pubkyRepo,
        activityRepo = activityRepo,
        hwWalletRepo = hwWalletRepo,
        suggestionsRepo = suggestionsRepo,
        usdtRepo = usdtRepo,
    )

    private fun hardwareWallet(balanceSats: ULong = 0uL) = HwWallet(
        id = "device-id",
        name = "Trezor",
        model = "Safe 5",
        transportType = TransportType.USB,
        isConnected = true,
        balanceSats = balanceSats,
        activities = persistentListOf(),
    )
}
