package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkQuote
import javax.inject.Inject

/**
 * The Update Spending Balance modal's view of [MoveToSparkManager], which keeps a move running after the modal closes.
 * The modal is a sheet that outlives no navigation entry, so it calls [open] and [close] as it shows and hides.
 */
@HiltViewModel
class MoveToSparkViewModel @Inject constructor(
    private val manager: MoveToSparkManager,
) : ViewModel() {

    val uiState = manager.uiState

    private var screen: Int? = null

    /** Quotes a new move when the modal shows, unless a move is running or still shows its outcome. */
    fun open() {
        if (screen == null) screen = manager.start()
    }

    /** Forgets a settled move once the modal hides, so the next opening quotes anew. */
    fun close() {
        screen?.let { manager.reset(it) }
        screen = null
    }

    /** Quotes moving [targetSats], or everything the channels can send. */
    fun quote(targetSats: ULong? = null) = manager.quote(targetSats)

    fun move() = manager.move()

    override fun onCleared() = close()
}

@Immutable
sealed interface MoveToSparkUiState {
    data object Quoting : MoveToSparkUiState

    data class Confirm(val quote: MoveToSparkQuote, val isMoving: Boolean = false) : MoveToSparkUiState

    /** [hasRest]: the channels can still send enough to move again, as after a smaller amount. */
    data class Done(val outcome: MoveToSparkOutcome, val hasRest: Boolean = false) : MoveToSparkUiState

    /** [amountSats] did not route in one payment; [smallerSats] is the next amount to try, if any is left. */
    data class TooLarge(val amountSats: ULong, val smallerSats: ULong?) : MoveToSparkUiState

    data class Failed(val failure: MoveToSparkFailure) : MoveToSparkUiState
}

enum class MoveToSparkFailure { NOTHING_TO_MOVE, SPARK_UNAVAILABLE, UNKNOWN }
