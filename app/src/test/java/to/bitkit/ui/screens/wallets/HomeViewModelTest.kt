package to.bitkit.ui.screens.wallets

import android.content.Context
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.WidgetsData
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
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest : BaseUnitTest() {

    private val context = mock<Context>()
    private val walletRepo = mock<WalletRepo>()
    private val widgetsRepo = mock<WidgetsRepo>()
    private val currencyRepo = mock<CurrencyRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val transferRepo = mock<TransferRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val activityRepo = mock<ActivityRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()
    private val suggestionsRepo = mock<SuggestionsRepo>()

    private lateinit var hardwareWallets: MutableStateFlow<ImmutableList<HwWallet>>
    private lateinit var suggestions: MutableStateFlow<List<Suggestion>>

    @Before
    fun setUp() {
        hardwareWallets = MutableStateFlow(persistentListOf())
        suggestions = MutableStateFlow(emptyList())

        whenever(context.getString(R.string.lightning__transfer_in_progress)).thenReturn("Transfer in progress")
        whenever(walletRepo.balanceState).thenReturn(MutableStateFlow(BalanceState()))
        whenever(widgetsRepo.widgetsDataFlow).thenReturn(MutableStateFlow(WidgetsData()))
        whenever(widgetsRepo.articlesFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(widgetsRepo.factsFlow).thenReturn(MutableStateFlow(emptyList()))
        whenever(currencyRepo.currencyState).thenReturn(MutableStateFlow(CurrencyState()))
        whenever(settingsStore.data).thenReturn(MutableStateFlow(SettingsData()))
        whenever(transferRepo.activeTransfers).thenReturn(MutableStateFlow(emptyList()))
        whenever(transferRepo.forceCloseRemainingDuration).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.isAuthenticated).thenReturn(MutableStateFlow(false))
        whenever(pubkyRepo.displayName).thenReturn(MutableStateFlow(null))
        whenever(pubkyRepo.displayImageUri).thenReturn(MutableStateFlow(null))
        whenever(activityRepo.activitiesChanged).thenReturn(MutableStateFlow(0L))
        whenever { activityRepo.getActivities(limit = 1u) }.thenReturn(Result.success(emptyList()))
        whenever(hwWalletRepo.wallets).thenReturn(hardwareWallets)
        whenever(suggestionsRepo.suggestionsFlow).thenReturn(suggestions)
    }

    @Test
    fun `updates suggestions from repo`() = test {
        suggestions.value = listOf(Suggestion.HARDWARE)
        val sut = createViewModel()

        advanceUntilIdle()

        assertTrue(Suggestion.HARDWARE in sut.uiState.value.suggestions)
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

    private fun createViewModel() = HomeViewModel(
        context = context,
        walletRepo = walletRepo,
        widgetsRepo = widgetsRepo,
        currencyRepo = currencyRepo,
        settingsStore = settingsStore,
        transferRepo = transferRepo,
        pubkyRepo = pubkyRepo,
        activityRepo = activityRepo,
        hwWalletRepo = hwWalletRepo,
        suggestionsRepo = suggestionsRepo,
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
