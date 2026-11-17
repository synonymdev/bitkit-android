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
import to.bitkit.repositories.MoveToSparkMath
import to.bitkit.repositories.MoveToSparkOutcome
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

    private val quote = MoveToSparkQuote(invoice = "lnbcrt1spark", amountSats = 50_000uL, feeSats = 10uL)
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
        whenever { moveToSparkRepo.quote(anyOrNull()) }.thenReturn(Result.success(quote))
        whenever { moveToSparkRepo.move(eq(quote)) }
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
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        whenever(moveToSparkRepo.movableSats()).thenReturn(MoveToSparkMath.MIN_MOVE_SATS * 2uL)
        val sut = createViewModel()
        sut.move()

        firstSettled.complete(Result.success(moved))

        assertEquals(MoveToSparkUiState.Done(moved, hasRest = true), sut.uiState.value)
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
        whenever(moveToSparkRepo.move(eq(quote))).doSuspendableAnswer { payment.await() }
        createViewModel().move()

        manager.reset(screen = 1)
        val reopened = createViewModel()
        assertEquals(MoveToSparkUiState.Confirm(quote, isMoving = true), reopened.uiState.value)

        payment.complete(Result.success(moved))

        assertEquals(MoveToSparkUiState.Done(moved), reopened.uiState.value)
        verify(moveToSparkRepo, times(1)).quote(anyOrNull())
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
        whenever(moveToSparkRepo.move(eq(quote))).thenReturn(Result.success(moved))
        createViewModel().move()

        manager.reset(screen = 1)
        val reopened = createViewModel()

        assertEquals(MoveToSparkUiState.Confirm(quote), reopened.uiState.value)
        verify(moveToSparkRepo, times(2)).quote(anyOrNull())
    }

    @Test
    fun `a closed screen's late cleanup leaves the reopened screen's quote`() = test {
        createViewModel()
        val reopened = createViewModel()

        manager.reset(screen = 1)

        assertEquals(MoveToSparkUiState.Confirm(quote), reopened.uiState.value)
    }

    @Test
    fun `wiping the wallet drops an in-flight move and quotes anew on return`() = test {
        createViewModel().move()

        manager.resetForWipe()
        val reopened = createViewModel()
        firstSettled.complete(Result.success(moved))

        assertEquals(MoveToSparkUiState.Confirm(quote), reopened.uiState.value)
        verify(moveToSparkRepo, times(2)).quote(anyOrNull())
    }

    @Test
    fun `a settled move closes the channels it emptied once it moved`() = test {
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        whenever(moveToSparkRepo.move(eq(quote))).thenReturn(Result.success(moved))

        createViewModel().move()

        verify(closeChannelUseCase).closeEmptiedByMove(listOf(openChannel))
    }

    @Test
    fun `an in-flight move closes nothing until it settles`() = test {
        lightningState.value = LightningState(channels = persistentListOf(openChannel))
        createViewModel().move()
        verify(closeChannelUseCase, never()).closeEmptiedByMove(any())

        firstSettled.complete(Result.success(moved))

        verify(closeChannelUseCase).closeEmptiedByMove(listOf(openChannel))
    }

    @Test
    fun `a move that fails late closes nothing`() = test {
        createViewModel().move()

        firstSettled.complete(Result.failure(MoveToSparkError.PaymentFailed(null)))

        verify(closeChannelUseCase, never()).closeEmptiedByMove(any())
    }

    @Test
    fun `closing the modal after a settled move quotes anew on the next opening`() = test {
        whenever(moveToSparkRepo.move(eq(quote))).thenReturn(Result.success(moved))
        val sut = createViewModel()
        sut.move()

        sut.close()
        sut.open()

        assertEquals(MoveToSparkUiState.Confirm(quote), sut.uiState.value)
    }

    private fun createViewModel() = MoveToSparkViewModel(manager).also { it.open() }
}
