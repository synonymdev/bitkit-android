package to.bitkit.ui.settings.lightning

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.repositories.SparkExitFixtures
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SparkExitViewModelTest : BaseUnitTest() {
    private val repo = mock<SparkExitRepo> { on { state } doReturn MutableStateFlow(SparkExitState()) }

    @Test
    fun `entering the funding step again offers both choices while nothing was paid`() = test {
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(0uL))
        val sut = SparkExitViewModel(repo)
        sut.onFundExternally()
        sut.stopWatchingFundingAddress()

        sut.onFundingShown()

        assertFalse(sut.uiState.value.showsFundingAddress)
        assertFalse(sut.uiState.value.fundFromSavings)
    }

    @Test
    fun `entering the funding step keeps the address once a payment reached it`() = test {
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(5_000uL))
        val sut = SparkExitViewModel(repo)

        sut.onFundingShown()

        assertTrue(sut.uiState.value.showsFundingAddress)
        sut.stopWatchingFundingAddress()
    }

    @Test
    fun `funding from another wallet continues only once the address holds what the quote asks`() {
        val quote = SparkExitQuote(SparkExitFixtures.prepared(funding = 20_000uL))
        assertFalse(SparkExitUiState().canContinueFunding)
        assertFalse(SparkExitUiState(quote = quote, fundingSeenSats = 8_000uL).canContinueFunding)
        assertTrue(SparkExitUiState(quote = quote, fundingSeenSats = 20_000uL).canContinueFunding)
    }
}
