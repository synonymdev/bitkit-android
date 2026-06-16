package to.bitkit.repositories

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BalanceState
import to.bitkit.models.HwWallet
import to.bitkit.models.Suggestion
import to.bitkit.models.TransportType
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SuggestionsRepoTest : BaseUnitTest() {
    private lateinit var sut: SuggestionsRepo

    private val walletRepo = mock<WalletRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val transferRepo = mock<TransferRepo>()
    private val pubkyRepo = mock<PubkyRepo>()
    private val hwWalletRepo = mock<HwWalletRepo>()

    private lateinit var hardwareWallets: MutableStateFlow<ImmutableList<HwWallet>>

    private fun setUp(
        settings: SettingsData,
        wallets: ImmutableList<HwWallet> = persistentListOf(),
    ) {
        hardwareWallets = MutableStateFlow(wallets)

        whenever(walletRepo.balanceState).thenReturn(MutableStateFlow(BalanceState()))
        whenever(settingsStore.data).thenReturn(flowOf(settings))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(pubkyRepo.isAuthenticated).thenReturn(MutableStateFlow(false))
        whenever(hwWalletRepo.wallets).thenReturn(hardwareWallets)

        sut = SuggestionsRepo(
            bgDispatcher = testDispatcher,
            walletRepo = walletRepo,
            settingsStore = settingsStore,
            transferRepo = transferRepo,
            pubkyRepo = pubkyRepo,
            hwWalletRepo = hwWalletRepo,
        )
    }

    @Before
    fun before() {
        setUp(SettingsData())
    }

    @Test
    fun `resetDismissedSuggestionsIfEmpty clears dismissed when no suggestions are visible`() = test {
        setUp(SettingsData(dismissedSuggestions = Suggestion.entries.map { it.name }))

        sut.resetDismissedSuggestionsIfEmpty()

        val captor = argumentCaptor<(SettingsData) -> SettingsData>()
        verify(settingsStore).update(captor.capture())
        val result = captor.firstValue(SettingsData(dismissedSuggestions = listOf(Suggestion.BUY.name)))
        assertTrue(result.dismissedSuggestions.isEmpty())
    }

    @Test
    fun `resetDismissedSuggestionsIfEmpty does nothing when suggestions are visible`() = test {
        setUp(SettingsData(dismissedSuggestions = emptyList()))

        sut.resetDismissedSuggestionsIfEmpty()

        verify(settingsStore, never()).update(any())
    }

    @Test
    fun `suggestionsFlow filters out dismissed suggestions`() = test {
        setUp(SettingsData(dismissedSuggestions = listOf(Suggestion.BUY.name)))

        val suggestions = sut.suggestionsFlow.first()

        assertTrue(Suggestion.BUY !in suggestions)
    }

    @Test
    fun `suggestionsFlow shows hardware suggestion without paired hardware`() = test {
        setUp(SettingsData())

        val suggestions = sut.suggestionsFlow.first()

        assertTrue(Suggestion.HARDWARE in suggestions)
    }

    @Test
    fun `suggestionsFlow hides hardware suggestion with paired hardware`() = test {
        setUp(SettingsData(), wallets = persistentListOf(hardwareWallet()))

        val suggestions = sut.suggestionsFlow.first()

        assertFalse(Suggestion.HARDWARE in suggestions)
    }

    private fun hardwareWallet() = HwWallet(
        id = "device-id",
        name = "Trezor",
        model = "Safe 5",
        transportType = TransportType.USB,
        isConnected = true,
        balanceSats = 0uL,
        activities = persistentListOf(),
    )
}
