package to.bitkit.ui.settings.lightning

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.ChannelDetails
import org.lightningdevkit.ldknode.PaymentDetails
import to.bitkit.async.appScope
import to.bitkit.di.BgDispatcher
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.MoveToSparkError
import to.bitkit.repositories.MoveToSparkMath
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.usecases.CloseChannelUseCase
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Update Spending Balance modal's state for the app's lifetime, so a move and its outcome outlive the modal:
 * leaving while the payment is under way no longer stops the wait, and opening the modal again shows that move. Once a
 * move settles, it closes the channels the move emptied.
 */
@Singleton
class MoveToSparkManager @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
    private val moveToSparkRepo: MoveToSparkRepo,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
    private val closeChannelUseCase: CloseChannelUseCase,
) {
    companion object {
        private const val TAG = "MoveToSparkManager"
    }

    private val scope = appScope(bgDispatcher, TAG)

    private val _uiState = MutableStateFlow<MoveToSparkUiState>(MoveToSparkUiState.Quoting)
    val uiState: StateFlow<MoveToSparkUiState> = _uiState.asStateFlow()

    /** Counts quotes, moves and resets; a quote or move writes its result only while it is still the latest. */
    private val generation = AtomicInteger(0)
    private val latestScreen = AtomicInteger(0)
    private var quoteJob: Job? = null
    private var moveJob: Job? = null

    private val _hasRetainedMove = MutableStateFlow(false)

    /**
     * A move was started and its outcome is still the screen's, so opening the screen again keeps it and its entry
     * points stay, also once no balance is left to move.
     */
    val hasRetainedMove: StateFlow<Boolean> = _hasRetainedMove.asStateFlow()

    private var keepsOutcome: Boolean
        get() = _hasRetainedMove.value
        set(value) = _hasRetainedMove.update { value }

    /** A swiped payment that has not settled yet; leaving the screen keeps it, so it is never quoted over. */
    @Volatile
    private var isMoveInFlight = false

    /**
     * Opens the screen: quotes a new move, unless one is quoting or a move is running or still shows its outcome.
     * Returns the screen's token for [reset].
     */
    fun start(): Int {
        val screen = latestScreen.incrementAndGet()
        if (!keepsOutcome && quoteJob?.isActive != true) quote()
        return screen
    }

    /**
     * Forgets the last move once its [screen] is left, unless its payment is still in flight or a newer screen opened
     * before this one closed.
     */
    fun reset(screen: Int) {
        if (screen != latestScreen.get() || isMoveInFlight) return
        generation.incrementAndGet()
        quoteJob?.cancel()
        keepsOutcome = false
        _uiState.update { MoveToSparkUiState.Quoting }
    }

    /** Drops the move, its outcome and any quote when the wallet is wiped, so the next wallet starts on a new quote. */
    fun resetForWipe() {
        generation.incrementAndGet()
        quoteJob?.cancel()
        moveJob?.cancel()
        keepsOutcome = false
        isMoveInFlight = false
        _uiState.update { MoveToSparkUiState.Quoting }
    }

    /** Quotes moving everything the channels can send, in parts when one payment does not route. */
    fun quote() = quote(moved = null)

    private fun quote(moved: MoveToSparkOutcome.Moved?) {
        val quoteGeneration = generation.incrementAndGet()
        keepsOutcome = false
        _uiState.update { MoveToSparkUiState.Quoting }
        quoteJob?.cancel()
        quoteJob = scope.launch {
            val quoted = moveToSparkRepo.quoteAll().fold(
                onSuccess = { MoveToSparkUiState.Confirm(it) },
                onFailure = {
                    if (moved != null && it is MoveToSparkError.TooLarge) {
                        MoveToSparkUiState.Done(moved)
                    } else {
                        it.toUiState()
                    }
                },
            )
            if (generation.get() == quoteGeneration) _uiState.update { quoted }
        }
    }

    fun move() {
        val confirm = _uiState.value as? MoveToSparkUiState.Confirm ?: return
        if (confirm.isMoving) return
        val plan = confirm.plan
        val channelsBefore = lightningRepo.lightningState.value.channels.toList()
        val moveGeneration = generation.incrementAndGet()
        keepsOutcome = true
        isMoveInFlight = true
        _uiState.update { MoveToSparkUiState.Confirm(plan, isMoving = true) }
        moveJob = scope.launch {
            val paymentsBefore = lightningRepo.listPaymentsOrNull()
            try {
                moveToSparkRepo.moveAll(plan).fold(
                    onSuccess = { outcome ->
                        val inFlight = outcome as? MoveToSparkOutcome.InFlight
                        val settled = inFlight?.let {
                            async(start = CoroutineStart.UNDISPATCHED) { moveToSparkRepo.awaitSettled(it) }
                        }
                        val done = done(outcome)
                        if (done == null) {
                            requote(moveGeneration, outcome as? MoveToSparkOutcome.Moved)
                        } else if (generation.get() == moveGeneration) {
                            _uiState.update { done }
                        }
                        if (outcome is MoveToSparkOutcome.Moved) closeEmptied(channelsBefore, paymentsBefore)
                        settled?.await()?.let {
                            showLateOutcome(it, inFlight.earlierSats, moveGeneration)
                            if (it.isSuccess || inFlight.earlierSats > 0uL) closeEmptied(channelsBefore, paymentsBefore)
                        }
                    },
                    onFailure = { error ->
                        if (error is MoveToSparkError.FeeRose) {
                            // As elsewhere when a fee rises, the modal shows the new fee for what is left.
                            val moved = error.movedSats.takeIf { it > 0uL }
                                ?.let { MoveToSparkOutcome.Moved(it, feeSats = 0uL) }
                            if (moved != null) closeEmptied(channelsBefore, paymentsBefore)
                            requote(moveGeneration, moved)
                        } else if (generation.get() == moveGeneration) {
                            _uiState.update { error.toUiState() }
                        }
                    },
                )
            } finally {
                if (generation.get() == moveGeneration) isMoveInFlight = false
            }
        }
    }

    private suspend fun done(outcome: MoveToSparkOutcome): MoveToSparkUiState.Done? {
        walletRepo.syncNodeAndWallet()
        val hasRest = outcome is MoveToSparkOutcome.Moved &&
            MoveToSparkMath.hasMovableBalance(moveToSparkRepo.movableSats())
        return if (hasRest) null else MoveToSparkUiState.Done(outcome)
    }

    private fun requote(moveGeneration: Int, moved: MoveToSparkOutcome.Moved?) {
        if (generation.get() != moveGeneration) return
        isMoveInFlight = false
        quote(moved)
    }

    private fun closeEmptied(channelsBefore: List<ChannelDetails>, paymentsBefore: List<PaymentDetails>?) {
        scope.launch { closeChannelUseCase.closeEmptiedByMove(channelsBefore, paymentsBefore) }
    }

    private suspend fun showLateOutcome(
        settled: Result<MoveToSparkOutcome.Moved>,
        earlierSats: ULong,
        moveGeneration: Int,
    ) {
        if (generation.get() != moveGeneration || !uiState.value.isInFlight()) return
        val moved = settled.getOrNull() ?: earlierSats.takeIf { it > 0uL }
            ?.let { MoveToSparkOutcome.Moved(it, feeSats = 0uL) }
        val late = if (moved == null) MoveToSparkUiState.Failed(MoveToSparkFailure.UNKNOWN) else done(moved)
        if (late == null) {
            if (uiState.value.isInFlight()) requote(moveGeneration, moved)
            return
        }
        _uiState.update { if (generation.get() == moveGeneration && it.isInFlight()) late else it }
    }

    private fun MoveToSparkUiState.isInFlight(): Boolean =
        this is MoveToSparkUiState.Done && outcome is MoveToSparkOutcome.InFlight

    private fun Throwable.toUiState(): MoveToSparkUiState = when (this) {
        is MoveToSparkError.NothingToMove -> MoveToSparkUiState.Failed(MoveToSparkFailure.NOTHING_TO_MOVE)
        is MoveToSparkError.SparkUnavailable -> MoveToSparkUiState.Failed(MoveToSparkFailure.SPARK_UNAVAILABLE)
        else -> MoveToSparkUiState.Failed(MoveToSparkFailure.UNKNOWN)
    }
}
