package to.bitkit.ui.settings.lightning

import android.content.Context
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.FeeRate
import to.bitkit.repositories.OnchainSendChangedError
import to.bitkit.repositories.OnchainSendNotDispatchedError
import to.bitkit.repositories.SparkExitFeeBumpOption
import to.bitkit.repositories.SparkExitFixtures
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitNetworkFees
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
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)
        sut.onFundExternally()
        sut.stopWatchingFundingAddress()

        sut.onFundingShown()

        assertFalse(sut.uiState.value.showsFundingAddress)
        assertFalse(sut.uiState.value.fundFromSavings)
    }

    @Test
    fun `entering the funding step keeps the address once a payment reached it`() = test {
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(5_000uL))
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)

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
    fun `progress hints at rising fees and offers a higher fee savings can pay for`() {
        val running = SparkExitFixtures.record(SparkExitRecord.Phase.Running)
            .adopt(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))), 0L)
        val rising = SparkExitUiState(
            record = running,
            tipHeight = 1_000u,
            networkFees = SparkExitNetworkFees(9uL, 6uL)
        )
        assertTrue(rising.canPayHigherFee)
        assertTrue(rising.showsFeeRose)
        assertFalse(rising.copy(networkFees = SparkExitNetworkFees(2uL, 1uL)).canPayHigherFee)

        val fast = SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 4_200uL, 4_620uL)
        assertTrue(SparkExitFeeBumpUiState(spendableSats = 4_620uL).canPay(fast))
        assertFalse(SparkExitFeeBumpUiState(spendableSats = 4_619uL).canPay(fast))
        assertTrue(SparkExitFeeBumpUiState(spendableSats = 0uL).canPay(fast.copy(extraSats = 0uL, totalSats = 0uL)))
        assertTrue(SparkExitFeeBumpUiState(options = persistentListOf(fast), spendableSats = 1_000uL).isSavingsShort)
        assertFalse(SparkExitFeeBumpUiState(options = persistentListOf(fast), spendableSats = 5_000uL).isSavingsShort)
    }

    @Test
    fun `a higher fee whose savings payment now costs more is priced again for another confirm`() = test {
        val fast = SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 4_200uL, 4_620uL)
        whenever(repo.spendableSavingsSats()).thenReturn(Result.success(10_000uL))
        whenever(repo.feeBumpOptions()).thenReturn(Result.success(listOf(fast)))
        whenever(repo.payHigherFee(fast))
            .thenReturn(Result.failure(OnchainSendNotDispatchedError(OnchainSendChangedError(preparedFeeSats = 600uL))))
        val context = mock<Context> { on { getString(any()) } doReturn "Fees changed" }
        val sut = SparkExitViewModel(context, repo, sparkRepo)
        sut.loadFeeBump()

        sut.payHigherFee()

        verify(repo, times(2)).feeBumpOptions()
        assertFalse(sut.uiState.value.feeBump.isPaying)
    }

    @Test
    fun `the next confirm after a refused higher fee approves the fee it was refused for`() = test {
        val fast = SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 4_200uL, 4_620uL)
        // Savings' coin selection needs 600 sats where the estimate says 420.
        val floored = fast.copy(totalSats = 4_800uL)
        whenever(repo.spendableSavingsSats()).thenReturn(Result.success(10_000uL))
        whenever(repo.feeBumpOptions()).thenReturn(Result.success(listOf(fast)))
        whenever(repo.payHigherFee(fast))
            .thenReturn(Result.failure(OnchainSendNotDispatchedError(OnchainSendChangedError(preparedFeeSats = 600uL))))
        whenever(repo.payHigherFee(floored)).thenReturn(Result.success(Unit))
        val context = mock<Context> { on { getString(any()) } doReturn "Fees changed" }
        val sut = SparkExitViewModel(context, repo, sparkRepo)
        sut.loadFeeBump()

        sut.payHigherFee()
        assertEquals(floored, sut.uiState.value.feeBump.selectedOption)
        sut.payHigherFee()

        verify(repo).payHigherFee(floored)
    }

    @Test
    fun `a paid higher fee no longer shows the fee as too low`() {
        val running = SparkExitFixtures.record(SparkExitRecord.Phase.Running)
            .adopt(SparkExitFixtures.recovery(listOf(SparkExitFixtures.tx("n1"))), 0L)
        val slow = running.copy(
            broadcastLog = listOf(
                SparkExitRecord.BroadcastAttempt(
                    running.generation,
                    "n1",
                    1,
                    0,
                    0L,
                    "ok",
                    true,
                    acceptedAtHeight = 990u
                ),
            ),
        )
        assertEquals(2uL, SparkExitUiState(record = slow, tipHeight = 1_000u).lowFeeRate)
        val paid = slow.applyFeeBump(rate = 9uL, extraSats = 5_000uL, paymentTxid = "bump")
        assertNull(SparkExitUiState(record = paid, tipHeight = 1_000u).lowFeeRate)
    }

    @Test
    fun `done names the sweep that landed before a retry of leaves replaced it`() {
        val done = SparkExitFixtures.record(SparkExitRecord.Phase.Done)
            .copy(recovery = SparkExitFixtures.recovery(emptyList()), landedSweepTxid = "sweep")
        assertEquals("sweep", SparkExitUiState(record = done).sweepTxid)
    }

    @Test
    fun `confirm shows what leaves savings only when savings pays the fee funding`() {
        val quote = SparkExitQuote(SparkExitFixtures.prepared(funding = 20_000uL))
        val state = SparkExitUiState(quote = quote, savingsFeeSats = 300uL, fundFromSavings = true)

        assertEquals(20_300uL, state.savingsTotalSats)
        assertNull(state.copy(fundFromSavings = false).savingsTotalSats)
        assertNull(state.copy(savingsFeeSats = null).savingsTotalSats)
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
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)

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
