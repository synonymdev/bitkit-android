package to.bitkit.repositories

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.models.BalanceState
import to.bitkit.models.Suggestion
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertTrue

class SuggestionsRepoTest : BaseUnitTest() {
    private lateinit var sut: SuggestionsRepo

    private val walletRepo = mock<WalletRepo>()
    private val settingsStore = mock<SettingsStore>()
    private val transferRepo = mock<TransferRepo>()
    private val pubkyRepo = mock<PubkyRepo>()

    private fun setUp(settings: SettingsData) {
        whenever(walletRepo.balanceState).thenReturn(MutableStateFlow(BalanceState()))
        whenever(settingsStore.data).thenReturn(flowOf(settings))
        whenever(transferRepo.activeTransfers).thenReturn(flowOf(emptyList()))
        whenever(pubkyRepo.isAuthenticated).thenReturn(MutableStateFlow(false))

        sut = SuggestionsRepo(
            bgDispatcher = testDispatcher,
            walletRepo = walletRepo,
            settingsStore = settingsStore,
            transferRepo = transferRepo,
            pubkyRepo = pubkyRepo,
        )
    }

    @Before
    fun before() {
        setUp(SettingsData())
    }

    @Test
    fun `resetDismissedSuggestionsIfEmpty resets dismissed when no suggestions are visible`() = test {
        setUp(SettingsData(dismissedSuggestions = Suggestion.entries.map { it.name }))

        sut.resetDismissedSuggestionsIfEmpty()

        verify(settingsStore).update(any())
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
}
