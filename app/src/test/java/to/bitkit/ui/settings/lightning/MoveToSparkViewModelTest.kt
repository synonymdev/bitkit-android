package to.bitkit.ui.settings.lightning

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import to.bitkit.ext.createChannelDetails
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.LightningState
import to.bitkit.repositories.MoveToSparkError
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkPlan
import to.bitkit.repositories.MoveToSparkQuote
import to.bitkit.repositories.MoveToSparkRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.test.BaseUnitTest
import to.bitkit.usecases.CloseChannelUseCase
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MoveToSparkViewModelTest : BaseUnitTest() {
    private val moveToSparkRepo = mock<MoveToSparkRepo>()
    private val lightningRepo = mock<LightningRepo>()
    private val walletRepo = mock<WalletRepo>()
    private val closeChannelUseCase = mock<CloseChannelUseCase>()

    private val plan = MoveToSparkPlan(MoveToSparkQuote("lnbcrt1spark", amountSats = 50_000uL, feeSats = 10uL), 10uL)
    private val firstMove = MoveToSparkOutcome.InFlight(50_000uL, paymentHash = "hash_1", paymentId = "id_1")
    private val moved = MoveToSparkOutcome.Moved(50_000uL, feeSats = 7uL)
    private val openChannel = createChannelDetails().copy(channelId = "channel_1", isChannelReady = true)
    private val lightningState = MutableStateFlow(LightningState())
    private val secondMove = MoveToSparkOutcome.InFlight(50_000uL, paymentHash = "hash_2", paymentId = "id_2")
    private val firstSettled = CompletableDeferred<Result<MoveToSparkOutcome.Moved>>()
    private val secondSettled = CompletableDeferred<Result<MoveToSparkOutcome.Moved>>()
    private val manager by lazy {
        MoveToSparkManager(
            testDispatcher,
            moveToSparkRepo,
            lightningRepo,
            walletRepo,
            closeChannelUseCase
        )
    }

    @Before
    fun setUp() {
        whenever(lightningRepo.lightningState).thenReturn(lightningState)
        whenever(moveToSparkRepo.movableSats()).thenReturn(0uL)
        whenever { moveToSparkRepo.quoteAll() }.thenReturn(Result.success(plan))
        whenever { moveToSparkRepo.moveAll(eq(plan)) }
            .thenReturn(Result.success(firstMove), Result.success(secondMove))
        whenever { moveToSparkRepo.awaitSettled(eq(firstMove)) }.doSuspendableAnswer { firstSettled.await() }
        whenever { moveToSparkRepo.awaitSettled(eq(secondMove)) }.doSuspendableAnswer { secondSettled.await() }
        whenever { walletRepo.syncNodeAndWallet(anyOrNull()) }.thenReturn(Result.success(Unit))
    }

    @Test
    fun `late failure of the in-flight move shows it could not move`() = test {
        val sut = createViewModel()
        sut.move()
        assertEquals(firstMove, assertIs<MoveToSparkUiState.Done>(sut.uiState.value).outcome)

        firstSettled.complete(Result.failure(MoveToSparkError.PaymentFailed(null)))

        assertEquals(MoveToSparkUiState.Failed(MoveToSparkFailure.UNKNOWN), sut.uiState.value)
    }

    @Test
    fun `late failure of an older move leaves a newer move in flight`() = test {
        val sut = moveTwice()

        firstSettled.complete(Result.failure(MoveToSparkError.PaymentFailed(null)))

        assertEquals(secondMove, assertIs<MoveToSparkUiState.Done>(sut.uiState.value).outcome)
    }

    @Test
    fun `late success of the in-flight move shows it moved with the fee paid`() = test {
        val sut = createViewModel()
        sut.move()

        firstSettled.complete(Result.success(moved))

        assertEquals(MoveToSparkUiState.Done(moved), sut.uiState.value)
    }

    @Test
    fun `late success of an older move leaves a newer move in flight`() = test {
        val sut = moveTwice()

        firstSettled.complete(Result.success(moved))

        assertEquals(secondMove, assertIs<MoveToSparkUiState.Done>(sut.uiState.value).outcome)
    }

    private fun moveTwice(): MoveToSparkViewModel {
        val sut = createViewModel()
        sut.move()
        sut.quote()
        sut.move()
        assertEquals(secondMove, assertIs<MoveToSparkUiState.Done>(sut.uiState.value).outcome)
        return sut
    }

    @Test
    fun `leaving the screen while a move runs keeps the move and shows it again`() = test {
        val payment = CompletableDeferred<Result<MoveToSparkOutcome>>()
        whenever(moveToSparkRepo.moveAll(eq(plan))).doSuspendableAnswer { payment.await() }
        createViewModel().move()

        manager.reset(screen = 1)
        val reopened = createViewModel()
        assertEquals(MoveToSparkUiState.Confirm(plan, isMoving = true), reopened.uiState.value)

        payment.complete(Result.success(moved))

        assertEquals(MoveToSparkUiState.Done(moved), reopened.uiState.value)
        verify(moveToSparkRepo, times(1)).quoteAll()
    }

    @Test
    fun `leaving the screen while a move is in flight keeps waiting for its outcome`() = test {
        createViewModel().move()

        manager.reset(screen = 1)
        val reopened = createViewModel()
        firstSettled.complete(Result.success(moved))

        assertEquals(MoveToSparkUiState.Done(moved), reopened.uiState.value)
    }

    @Test
    fun `leaving the screen after a settled move quotes anew on return`() = test {
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(moved))
        createViewModel().move()

        manager.reset(screen = 1)
        val reopened = createViewModel()

        assertEquals(MoveToSparkUiState.Confirm(plan), reopened.uiState.value)
        verify(moveToSparkRepo, times(2)).quoteAll()
    }

    @Test
    fun `a closed screen's late cleanup leaves the reopened screen's quote`() = test {
        createViewModel()
        val reopened = createViewModel()

        manager.reset(screen = 1)

        assertEquals(MoveToSparkUiState.Confirm(plan), reopened.uiState.value)
    }

    @Test
    fun `wiping the wallet drops an in-flight move and quotes anew on return`() = test {
        createViewModel().move()

        manager.resetForWipe()
        val reopened = createViewModel()
        firstSettled.complete(Result.success(moved))

        assertEquals(MoveToSparkUiState.Confirm(plan), reopened.uiState.value)
        verify(moveToSparkRepo, times(2)).quoteAll()
    }

    @Test
    fun `a settled move closes the channels it emptied once it moved`() = test {
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(moved))

        createViewModel().move()

        verify(closeChannelUseCase).closeEmptiedByMove(eq(listOf(openChannel)), anyOrNull())
    }

    @Test
    fun `an in-flight move closes nothing until it settles`() = test {
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        createViewModel().move()
        verify(closeChannelUseCase, never()).closeEmptiedByMove(any(), anyOrNull())

        firstSettled.complete(Result.success(moved))

        verify(closeChannelUseCase).closeEmptiedByMove(eq(listOf(openChannel)), anyOrNull())
    }

    @Test
    fun `a last part that fails late still shows the balance updated after earlier parts moved`() = test {
        val lastPart = MoveToSparkOutcome.InFlight(20_000uL, "hash_3", "id_3", earlierSats = 30_000uL)
        val lastSettled = CompletableDeferred<Result<MoveToSparkOutcome.Moved>>()
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(lastPart))
        whenever { moveToSparkRepo.awaitSettled(eq(lastPart)) }.doSuspendableAnswer { lastSettled.await() }
        val sut = createViewModel()
        sut.move()

        lastSettled.complete(Result.failure(MoveToSparkError.PaymentFailed(null)))

        assertEquals(MoveToSparkUiState.Done(MoveToSparkOutcome.Moved(30_000uL, feeSats = 0uL)), sut.uiState.value)
    }

    @Test
    fun `a move that leaves a balance worth moving shows the fee for the rest`() = test {
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(moved))
        whenever(moveToSparkRepo.movableSats()).thenReturn(50_000uL)
        val sut = createViewModel()

        sut.move()

        assertEquals(MoveToSparkUiState.Confirm(plan), sut.uiState.value)
        verify(moveToSparkRepo, times(2)).quoteAll()
    }

    @Test
    fun `a rest that no longer routes after a move shows the balance updated`() = test {
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(moved))
        whenever(moveToSparkRepo.movableSats()).thenReturn(50_000uL)
        val sut = createViewModel()
        whenever(moveToSparkRepo.quoteAll()).thenReturn(Result.failure(MoveToSparkError.TooLarge(50_000uL)))

        sut.move()

        assertEquals(MoveToSparkUiState.Done(moved), sut.uiState.value)
    }

    @Test
    fun `a rest that no longer routes keeps the balance updated for the next opening`() = test {
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(moved))
        whenever(moveToSparkRepo.movableSats()).thenReturn(50_000uL)
        val sut = createViewModel()
        whenever(moveToSparkRepo.quoteAll()).thenReturn(Result.failure(MoveToSparkError.TooLarge(50_000uL)))
        sut.move()

        sut.open()

        assertEquals(MoveToSparkUiState.Done(moved), sut.uiState.value)
        verify(moveToSparkRepo, times(2)).quoteAll()
    }

    @Test
    fun `a move whose fee rose shows the new fee and closes what it emptied`() = test {
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.failure(MoveToSparkError.FeeRose(30_000uL)))
        val sut = createViewModel()

        sut.move()

        assertEquals(MoveToSparkUiState.Confirm(plan), sut.uiState.value)
        verify(moveToSparkRepo, times(2)).quoteAll()
        verify(closeChannelUseCase).closeEmptiedByMove(eq(listOf(openChannel)), anyOrNull())
    }

    @Test
    fun `a move whose last part fails late still closes what its earlier parts emptied`() = test {
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        val lastPart = firstMove.copy(earlierSats = 30_000uL)
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(lastPart))
        whenever { moveToSparkRepo.awaitSettled(eq(lastPart)) }.doSuspendableAnswer { firstSettled.await() }
        createViewModel().move()

        firstSettled.complete(Result.failure(MoveToSparkError.PaymentFailed(null)))

        verify(closeChannelUseCase).closeEmptiedByMove(eq(listOf(openChannel)), anyOrNull())
    }

    @Test
    fun `a move whose only part fails late closes nothing`() = test {
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        createViewModel().move()

        firstSettled.complete(Result.failure(MoveToSparkError.PaymentFailed(null)))

        verify(closeChannelUseCase, never()).closeEmptiedByMove(any(), anyOrNull())
    }

    @Test
    fun `closing the modal after a settled move quotes anew on the next opening`() = test {
        whenever(moveToSparkRepo.moveAll(eq(plan))).thenReturn(Result.success(moved))
        val sut = createViewModel()
        sut.move()

        sut.close()
        sut.open()

        assertEquals(MoveToSparkUiState.Confirm(plan), sut.uiState.value)
    }

    private fun createViewModel() = MoveToSparkViewModel(manager).also { it.open() }
}
