package to.bitkit.ui.settings.lightning

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import to.bitkit.repositories.SparkExitFixtures
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitState
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SparkExitViewModelTest : BaseUnitTest() {
    private val exitState = MutableStateFlow(SparkExitState())
    private val sparkState = MutableStateFlow(SparkState())
    private val repo = mock<SparkExitRepo> { on { state } doReturn exitState }
    private val sparkRepo = mock<SparkRepo> { on { state } doReturn sparkState }

    @Test
    fun `entering the funding step again offers both choices while nothing was paid`() = test {
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(0uL))
        val sut = SparkExitViewModel(repo, sparkRepo)
        sut.onFundExternally()
        sut.stopWatchingFundingAddress()

        sut.onFundingShown()

        assertFalse(sut.uiState.value.showsFundingAddress)
        assertFalse(sut.uiState.value.fundFromSavings)
    }

    @Test
    fun `entering the funding step keeps the address once a payment reached it`() = test {
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(5_000uL))
        val sut = SparkExitViewModel(repo, sparkRepo)

        sut.onFundingShown()

        assertTrue(sut.uiState.value.showsFundingAddress)
        sut.stopWatchingFundingAddress()
    }

    @Test
    fun `funding from another wallet continues only once the address holds what the quote asks`() {
        val quote = SparkExitQuote(SparkExitFixtures.prepared(funding = 20_000uL))
        assertFalse(SparkExitUiState().canContinueFunding)
        val paid = SparkExitUiState(quote = quote, fundingSeenCount = 1, fundingFeeRate = 2uL)
        assertFalse(paid.copy(fundingSeenSats = 8_000uL).canContinueFunding)
        assertTrue(paid.copy(fundingSeenSats = 20_000uL).canContinueFunding)
        // A second payment adds its input fee.
        assertFalse(paid.copy(fundingSeenSats = 20_000uL, fundingSeenCount = 2).canContinueFunding)
    }

    @Test
    fun `progress asks for at least dust before the funding address was read`() {
        val record = SparkExitFixtures.record(SparkExitRecord.Phase.NeedsFunding(68uL))

        assertEquals(
            SparkExitFundingState.Ask(SparkExitMath.FUNDING_DUST_SATS),
            SparkExitUiState(record = record).progressFunding,
        )
    }

    /** The qa-03 rerun, case 5: one 32,768-sat leaf lost to an older owner, 1,696 sats Spark could not exit. */
    @Test
    fun `a stopped exit names what spark's live balance holds beyond its leaves`() = test {
        val stopped = SparkExitFixtures.record(SparkExitRecord.Phase.Failed("Claimed by an older state")).copy(
            leafTotalSats = 32_768uL,
            lostLeaves = listOf(SparkExitRecord.LostLeaf("leaf-1", 32_768uL)),
        )
        exitState.value = SparkExitState(record = stopped)
        val sut = SparkExitViewModel(repo, sparkRepo)

        sparkState.value = SparkState(balanceSats = 34_464uL)

        assertEquals(1_696uL, sut.uiState.value.staysOnSparkSats)
        assertNull(sut.uiState.value.outsideExitSats)
        assertEquals(
            "Nothing could be recovered. ₿ 1 696 stays on Spark.",
            sparkExitFailedText("Nothing could be recovered.", "{amount} stays on Spark.", outsideAmount = "₿ 1 696"),
        )
        // Only the lost leaf is left on Spark's books: nothing is claimed to stay.
        sparkState.value = SparkState(balanceSats = 32_768uL)
        assertNull(sut.uiState.value.staysOnSparkSats)
    }

    @Test
    fun `a stopped exit says only what still stays on spark`() {
        val nothing = "Nothing could be recovered."
        val stays = "{amount} stays on Spark."

        assertEquals(nothing, sparkExitFailedText(nothing, stays, outsideAmount = null))
        assertEquals(
            "Nothing could be recovered. ₿ 3 000 stays on Spark.",
            sparkExitFailedText(nothing, stays, outsideAmount = "₿ 3 000"),
        )
    }
}
