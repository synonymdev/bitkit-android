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
import to.bitkit.di.BgDispatcher
import to.bitkit.ext.filterOpen
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.MoveToSparkError
import to.bitkit.repositories.MoveToSparkMath
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.async.appScope
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds the Move to Spark screen's state for the app's lifetime, so a move and its outcome outlive the screen: leaving
 * while the payment is under way no longer stops the wait, and opening the screen again shows that move.
 */
@Singleton
class MoveToSparkManager @Inject constructor(
    @BgDispatcher bgDispatcher: CoroutineDispatcher,
    private val moveToSparkRepo: MoveToSparkRepo,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
) {
    companion object {
        private const val TAG = "MoveToSparkManager"
    }

    private val scope = appScope(bgDispatcher, TAG)

    private val _uiState = MutableStateFlow<MoveToSparkUiState>(MoveToSparkUiState.Quoting)
    val uiState: StateFlow<MoveToSparkUiState> = _uiState.asStateFlow()

    /** Counts quotes, moves and resets; a quote or move writes its result only while it is still the latest. */
    private val generation = AtomicInteger(0)
    private var quoteJob: Job? = null

    /** A move was started and its outcome is still the screen's, so opening the screen again keeps it. */
    @Volatile
    private var keepsOutcome = false

    /** A swiped payment that has not settled yet; leaving the screen keeps it, so it is never quoted over. */
    @Volatile
    private var isMoveInFlight = false

    /** Opens the screen: quotes a new move, unless one is quoting or a move is running or still shows its outcome. */
    fun start() {
        if (keepsOutcome || quoteJob?.isActive == true) return
        quote()
    }

    /** Forgets the last move once its screen is left, unless its payment is still in flight. */
    fun reset() {
        if (isMoveInFlight) return
        generation.incrementAndGet()
        quoteJob?.cancel()
        keepsOutcome = false
        _uiState.update { MoveToSparkUiState.Quoting }
    }

    /** Quotes moving [targetSats], or everything the channels can send. */
    fun quote(targetSats: ULong? = null) {
        val quoteGeneration = generation.incrementAndGet()
        keepsOutcome = false
        _uiState.update { MoveToSparkUiState.Quoting }
        quoteJob?.cancel()
        quoteJob = scope.launch {
            val quoted = moveToSparkRepo.quote(targetSats).fold(
                onSuccess = { MoveToSparkUiState.Confirm(it) },
                onFailure = { it.toUiState() },
            )
            if (generation.get() == quoteGeneration) _uiState.update { quoted }
        }
    }

    fun move() {
        val confirm = _uiState.value as? MoveToSparkUiState.Confirm ?: return
        if (confirm.isMoving) return
        val quote = confirm.quote
        val moveGeneration = generation.incrementAndGet()
        keepsOutcome = true
        isMoveInFlight = true
        _uiState.update { MoveToSparkUiState.Confirm(quote, isMoving = true) }
        scope.launch {
            try {
                moveToSparkRepo.move(quote).fold(
                    onSuccess = { outcome ->
                        val settled = (outcome as? MoveToSparkOutcome.InFlight)?.let {
                            async(start = CoroutineStart.UNDISPATCHED) { moveToSparkRepo.awaitSettled(it) }
                        }
                        val done = done(outcome)
                        if (generation.get() == moveGeneration) _uiState.update { done }
                        settled?.await()?.let { showLateOutcome(it, moveGeneration) }
                    },
                    onFailure = { error ->
                        if (generation.get() == moveGeneration) _uiState.update { error.toUiState() }
                    },
                )
            } finally {
                if (generation.get() == moveGeneration) isMoveInFlight = false
            }
        }
    }

    private suspend fun done(outcome: MoveToSparkOutcome): MoveToSparkUiState.Done {
        walletRepo.syncNodeAndWallet()
        val hasRest = MoveToSparkMath.hasMovableBalance(moveToSparkRepo.movableSats())
        return MoveToSparkUiState.Done(outcome, emptiedChannelId(), hasRest)
    }

    private suspend fun showLateOutcome(settled: Result<MoveToSparkOutcome.Moved>, moveGeneration: Int) {
        if (generation.get() != moveGeneration || !uiState.value.isInFlight()) return
        val late = settled.fold(
            onSuccess = { done(it) },
            onFailure = { MoveToSparkUiState.Failed(MoveToSparkFailure.UNKNOWN) },
        )
        _uiState.update { if (generation.get() == moveGeneration && it.isInFlight()) late else it }
    }

    private fun MoveToSparkUiState.isInFlight(): Boolean =
        this is MoveToSparkUiState.Done && outcome is MoveToSparkOutcome.InFlight

    /** The channel to close after the move, when exactly one is open; with several, the user picks in Connections. */
    private fun emptiedChannelId(): String? = lightningRepo.lightningState.value.channels.filterOpen()
        .singleOrNull()?.channelId

    private fun Throwable.toUiState(): MoveToSparkUiState = when (this) {
        is MoveToSparkError.TooLarge -> MoveToSparkUiState.TooLarge(
            amountSats = amountSats,
            smallerSats = MoveToSparkMath.smallerAmount(amountSats),
        )
        is MoveToSparkError.NothingToMove -> MoveToSparkUiState.Failed(MoveToSparkFailure.NOTHING_TO_MOVE)
        is MoveToSparkError.SparkUnavailable -> MoveToSparkUiState.Failed(MoveToSparkFailure.SPARK_UNAVAILABLE)
        else -> MoveToSparkUiState.Failed(MoveToSparkFailure.UNKNOWN)
    }
}
