package to.bitkit.ui.settings.lightning

import android.content.Context
import com.synonym.bitkitcore.FeeRates
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.models.FeeRate
import to.bitkit.repositories.OnchainSendChangedError
import to.bitkit.repositories.OnchainSendNotDispatchedError
import to.bitkit.repositories.SparkExitCancel
import to.bitkit.repositories.SparkExitFeeBumpOption
import to.bitkit.repositories.SparkExitFixtures
import to.bitkit.repositories.SparkExitFundingState
import to.bitkit.repositories.SparkExitMath
import to.bitkit.repositories.SparkExitNetworkFees
import to.bitkit.repositories.SparkExitQuote
import to.bitkit.repositories.SparkExitRecord
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitSavingsFunding
import to.bitkit.repositories.SparkExitSavingsRetry
import to.bitkit.repositories.SparkExitState
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkState
import to.bitkit.test.BaseUnitTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class SparkExitViewModelTest : BaseUnitTest() {
    private val exitState = MutableStateFlow(SparkExitState())
    private val sparkState = MutableStateFlow(SparkState())
    private val repo = mock<SparkExitRepo> { on { state } doReturn exitState }
    private val sparkRepo = mock<SparkRepo> { on { state } doReturn sparkState }

    @Test
    fun `entering the funding step again offers both choices while nothing was paid`() = test {
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(0uL))
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)
        sut.onPayExternally()

        sut.onFundingShown()

        assertFalse(sut.uiState.value.showsFundingAddress)
        assertFalse(sut.uiState.value.fundFromSavings)
        assertFalse(sut.uiState.value.isReadingFundingAddress, "continue waits only for the first read")
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
    fun `only a pull to refresh shows the refresh indicator`() = test {
        val seen = mutableListOf<Boolean>()
        lateinit var sut: SparkExitViewModel
        whenever(repo.load(anyOrNull())).doSuspendableAnswer {
            seen += sut.uiState.value.isRefreshing
            Result.success(null)
        }
        whenever(repo.tick()).thenReturn(Result.success(null))
        sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)

        sut.refresh(showIndicator = false)
        sut.refresh()

        assertEquals(listOf(false, true), seen)
        assertFalse(sut.uiState.value.isRefreshing)
    }

    @Test
    fun `a started exit stays started until a screen opens its progress`() = test {
        whenever(repo.start(any())).thenReturn(Result.success(Unit))
        exitState.value = SparkExitState(record = SparkExitFixtures.record())
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)

        // No screen collects while the user went back from Confirm.
        sut.start()

        assertTrue(sut.uiState.value.isStarted)
        sut.onStartShown()
        assertFalse(sut.uiState.value.isStarted)
    }

    @Test
    fun `a start no screen showed is forgotten once its exit ends`() = test {
        whenever(repo.start(any())).thenReturn(Result.success(Unit))
        exitState.value = SparkExitState(record = SparkExitFixtures.record(SparkExitRecord.Phase.Running))
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)

        // The user left the exit screens while the start ran: nothing calls onStartShown.
        sut.start()
        assertTrue(sut.uiState.value.isStarted)

        exitState.value = SparkExitState()

        assertFalse(sut.uiState.value.isStarted, "the next exit's Quote screen would jump to Progress")
    }

    @Test
    fun `a cancel during the start leaves no start for the next exit`() = test {
        // The start wrote its record, then a cancel removed it before the savings payment took its lock.
        whenever(repo.start(any())).doSuspendableAnswer {
            exitState.value = SparkExitState(record = SparkExitFixtures.record())
            exitState.value = SparkExitState()
            Result.success(Unit)
        }
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)

        sut.start()

        assertFalse(sut.uiState.value.isStarted, "the next exit's Quote screen would jump to Progress")
    }

    @Test
    fun `cancelling an exit whose savings funding failed leaves no start for the next exit`() = test {
        // The exit started, but paying its fee funding failed before it was submitted.
        whenever(repo.start(any())).thenReturn(Result.failure(IllegalStateException("Savings payment failed")))
        exitState.value = SparkExitState(record = SparkExitFixtures.record())
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)

        // Progress was opened directly, so no screen consumes the start.
        sut.start()
        assertTrue(sut.uiState.value.isStarted)
        // The repo deletes the record of a cancelled exit nothing paid into.
        whenever(repo.cancel()).doSuspendableAnswer {
            exitState.value = SparkExitState()
            Result.success(SparkExitCancel.Cancelled)
        }
        sut.cancel()

        assertFalse(sut.uiState.value.isStarted, "the next exit's Quote screen would jump to Progress")
    }

    @Test
    fun `pay from savings totals only what another wallet's payment left missing`() {
        val state = SparkExitUiState(
            quote = SparkExitQuote(SparkExitFixtures.prepared(funding = 20_000uL)),
            fundFromSavings = true,
            savingsFeeSats = 500uL,
            fundingSeenSats = 8_000uL,
            fundingSeenCount = 1,
            fundingFeeRate = 2uL,
        )

        assertEquals(12_136uL, state.savingsFundingSats)
        assertEquals(12_636uL, state.savingsTotalSats, "savings pays the funding another wallet sent again")
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
    fun `pay to confirm starts on its own when the funding was already at the address`() = test {
        whenever(repo.loadQuote()).thenReturn(Result.success(SparkExitQuote(SparkExitFixtures.prepared())))
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(20_000uL))
        whenever(repo.start(any())).thenReturn(Result.failure(IllegalStateException("Start failed")))
        exitState.value = SparkExitState(fundingSeenSats = 20_000uL, fundingSeenCount = 1)
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)
        sut.loadQuote()

        sut.watchFundingAddress()

        assertTrue(sut.uiState.value.canAutoStartFunding)
        assertFalse(sut.uiState.value.canTapStartFunding)
        sut.startOnPayment()
        verify(repo).start(false)
        // The failed start waits for Try again; nothing starts on its own a second time this visit.
        assertFalse(sut.uiState.value.canAutoStartFunding)
        assertTrue(sut.uiState.value.canTapStartFunding)
        exitState.value = SparkExitState(fundingSeenSats = 30_000uL, fundingSeenCount = 2)
        sut.startOnPayment()
        verify(repo, times(1)).start(any())
        sut.stopWatchingFundingAddress()
    }

    @Test
    fun `pay to confirm waits for its first read before it starts`() = test {
        whenever(repo.loadQuote()).thenReturn(Result.success(SparkExitQuote(SparkExitFixtures.prepared())))
        whenever(repo.refreshFundingAddress()).thenReturn(Result.success(0uL))
        val sut = SparkExitViewModel(mock<Context>(), repo, sparkRepo)
        sut.loadQuote()
        exitState.value = SparkExitState(fundingSeenSats = 20_000uL, fundingSeenCount = 1)

        assertFalse(sut.uiState.value.canAutoStartFunding)
        sut.watchFundingAddress()
        assertTrue(sut.uiState.value.canAutoStartFunding)
        sut.stopWatchingFundingAddress()
    }

    @Test
    fun `pay to confirm never starts on its own while a start runs`() {
        val covered = SparkExitUiState(
            quote = SparkExitQuote(SparkExitFixtures.prepared()),
            fundingSeenSats = 20_000uL,
            fundingSeenCount = 1,
            hasPayReadFunding = true,
        )
        assertTrue(covered.canAutoStartFunding)
        assertFalse(covered.copy(isStarting = true).canAutoStartFunding)
        assertFalse(covered.copy(isStarting = true).canTapStartFunding)
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
        val onChain = SparkExitFeeBumpUiState(sharesNodeChain = true)
        assertTrue(onChain.copy(spendableSats = 4_620uL).canPay(fast))
        assertFalse(onChain.copy(spendableSats = 4_619uL).canPay(fast))
        assertTrue(onChain.copy(spendableSats = 0uL).canPay(fast.copy(extraSats = 0uL, totalSats = 0uL)))
        assertTrue(onChain.copy(options = persistentListOf(fast), spendableSats = 1_000uL).isSavingsShort)
        assertFalse(onChain.copy(options = persistentListOf(fast), spendableSats = 5_000uL).isSavingsShort)
        // Savings pays on the node's chain: while Spark reads another, only a rate with nothing to pay is offered.
        val offChain = SparkExitFeeBumpUiState(spendableSats = 5_000uL, sharesNodeChain = false)
        assertFalse(offChain.canPay(fast))
        assertTrue(offChain.canPay(fast.copy(extraSats = 0uL, totalSats = 0uL)))
    }

    @Test
    fun `a higher fee whose savings payment now costs more is priced again for another confirm`() = test {
        val fast = SparkExitFeeBumpOption(FeeRate.FAST, 9uL, 4_200uL, 4_620uL)
        whenever(repo.spendableSavingsSats()).thenReturn(Result.success(10_000uL))
        whenever(repo.feeBumpOptions()).thenReturn(Result.success(listOf(fast)))
        whenever(repo.payHigherFee(fast))
            .thenReturn(Result.failure(OnchainSendNotDispatchedError(OnchainSendChangedError(preparedFeeSats = 600uL))))
        val context = mock<Context> { on { getString(any()) } doReturn "Fees changed" }
        val sut = SparkExitViewModel(context, repo, sparkRepo).apply { sharesNodeChain = { true } }
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
        val sut = SparkExitViewModel(context, repo, sparkRepo).apply { sharesNodeChain = { true } }
        sut.loadFeeBump()

        sut.payHigherFee()
        assertEquals(floored, sut.uiState.value.feeBump.selectedOption)
        sut.payHigherFee()

        verify(repo).payHigherFee(floored)
    }

    @Test
    fun `the next funding after a start refused for a higher savings fee prices at least that fee`() = test {
        whenever(repo.savingsFundingFee(anyOrNull())).thenReturn(Result.success(SparkExitSavingsFunding(420uL, true)))
        whenever(repo.start(true))
            .thenReturn(Result.failure(OnchainSendNotDispatchedError(OnchainSendChangedError(preparedFeeSats = 600uL))))
        val context = mock<Context> { on { getString(any()) } doReturn "Fees changed" }
        val sut = SparkExitViewModel(context, repo, sparkRepo)
        sut.loadSavingsFunding()
        sut.onFundFromSavings()

        sut.start()
        sut.loadSavingsFunding()

        // Savings' coin selection needs 600 sats where the estimate says 420: the next funding prices that.
        verify(repo).savingsFundingFee(null)
        verify(repo).savingsFundingFee(600uL)
    }

    @Test
    fun `progress pays a start's refused savings payment at its shown price, then prices a higher fee`() = test {
        val refusal = SparkExitRecord.SavingsRefusal(20_000uL, 650uL)
        exitState.value = SparkExitState(record = SparkExitFixtures.record().copy(savingsRefusal = refusal))
        val retry = SparkExitSavingsRetry(20_000uL, 650uL, FeeRates(fast = 7u, mid = 4u, slow = 1u), canPay = true)
        whenever(repo.priceSavingsRefusal()).thenReturn(Result.success(retry))
        whenever(repo.paySavingsRefusal(retry))
            .thenReturn(Result.failure(OnchainSendNotDispatchedError(OnchainSendChangedError(preparedFeeSats = 800uL))))
        val context = mock<Context> { on { getString(any()) } doReturn "Fees changed" }
        val sut = SparkExitViewModel(context, repo, sparkRepo)
        sut.startSavingsRetryPricing()
        sut.loadSavingsRetry()
        assertEquals(20_650uL, sut.uiState.value.savingsRetryOffer?.totalSats)
        assertTrue(sut.uiState.value.offersSavingsRetry)

        // A higher price published after the screen showed 650 is never what the tap pays.
        val newer = retry.copy(feeSats = 900uL)
        whenever(repo.priceSavingsRefusal()).thenReturn(Result.success(newer))
        sut.loadSavingsRetry()
        sut.retrySavingsPayment(retry)

        verify(repo).paySavingsRefusal(retry)
        verify(repo, never()).paySavingsRefusal(newer)
        verify(repo, never()).retrySavingsPayment()
        verify(repo, times(3)).priceSavingsRefusal()
        // Once the refusal is at 1,000, the latest price at 900 is never offered.
        val raised = SparkExitFixtures.record().copy(savingsRefusal = refusal.copy(feeSats = 1_000uL))
        exitState.value = SparkExitState(record = raised)
        assertNull(sut.uiState.value.savingsRetryOffer)
    }

    @Test
    fun `a refused savings payment is priced only while progress is open`() = test {
        val refusal = SparkExitRecord.SavingsRefusal(20_000uL, 650uL)
        exitState.value = SparkExitState(record = SparkExitFixtures.record().copy(savingsRefusal = refusal))
        // No price yet, as before the node runs: pricing goes on every 15 seconds.
        whenever(repo.priceSavingsRefusal()).thenReturn(Result.failure(IllegalStateException("Node not running")))
        val sut = SparkExitViewModel(mock(), repo, sparkRepo)

        sut.loadSavingsRetry()
        verify(repo, never()).priceSavingsRefusal()

        sut.startSavingsRetryPricing()
        sut.startSavingsRetryPricing()
        sut.loadSavingsRetry()
        verify(repo, times(1)).priceSavingsRefusal()

        // A screen closing after another opened leaves the open one priced.
        sut.stopSavingsRetryPricing()
        delay(16.seconds)
        verify(repo, times(2)).priceSavingsRefusal()

        // The last screen closing stops the pricing still running, and a refresh never starts it again.
        sut.stopSavingsRetryPricing()
        delay(16.seconds)
        sut.loadSavingsRetry()
        verify(repo, times(2)).priceSavingsRefusal()
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
    fun `confirm shows what leaves savings only when savings pays the fee funding`() {
        val quote = SparkExitQuote(SparkExitFixtures.prepared(funding = 20_000uL))
        val state = SparkExitUiState(quote = quote, savingsFeeSats = 300uL, fundFromSavings = true)

        assertEquals(20_300uL, state.savingsTotalSats)
        assertNull(state.copy(fundFromSavings = false).savingsTotalSats)
        assertNull(state.copy(savingsFeeSats = null).savingsTotalSats)
    }

    @Test
    fun `emergency fees go to another wallet only once savings is priced and can't pay`() {
        val pricing = SparkExitUiState()
        assertTrue(pricing.isPricingSavings)
        assertFalse(pricing.paysExternally)
        assertFalse(pricing.copy(savingsFeeSats = 300uL, canFundFromSavings = true).paysExternally)
        assertTrue(pricing.copy(savingsFeeSats = 300uL, canFundFromSavings = false).paysExternally)
        assertTrue(pricing.copy(hasSavingsFeeFailed = true).paysExternally)
        assertTrue(
            pricing.copy(savingsFeeSats = 300uL, canFundFromSavings = true, showsFundingAddress = true).paysExternally
        )
    }

    @Test
    fun `progress asks for at least dust before the funding address was read`() {
        val record = SparkExitFixtures.record(SparkExitRecord.Phase.NeedsFunding(68uL))

        assertEquals(
            SparkExitFundingState.Ask(SparkExitMath.FUNDING_DUST_SATS),
            SparkExitUiState(record = record).progressFunding,
        )
    }

    @Test
    fun `progress says a savings payment may still land only while the exit waits for it`() {
        val paid = SparkExitFixtures.record().copy(fundingPaymentTxid = "pending")
        val landing = SparkExitUiState(
            record = paid,
            fundingState = SparkExitFundingState.Paid,
            isSavingsSendUnresolved = true,
        )

        assertTrue(landing.isSavingsSendLanding)
        assertEquals(SparkExitFundingState.Paid, landing.progressFunding)
        assertFalse(landing.copy(isSavingsSendUnresolved = false).isSavingsSendLanding, "accepted")
        assertFalse(landing.copy(isSavingsPaymentRefused = true).isSavingsSendLanding, "refused: its own retry shows")
        val seen = landing.copy(fundingState = SparkExitFundingState.Seen)
        assertFalse(seen.isSavingsSendLanding, "it reached the address")
        // Before the first read of the address, the kept payment still holds the ask.
        assertEquals(SparkExitFundingState.Paid, landing.copy(fundingState = null).progressFunding)
        // Once the own-payment wait forgets it, the ask comes back, even with the journal still unresolved.
        val ask = SparkExitFundingState.Ask(20_000uL)
        val forgotten = landing.copy(record = paid.copy(fundingPaymentTxid = null), fundingState = ask)
        assertFalse(forgotten.isSavingsSendLanding)
        assertEquals(ask, forgotten.progressFunding)
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
