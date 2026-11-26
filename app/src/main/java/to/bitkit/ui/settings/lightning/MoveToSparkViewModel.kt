package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkPlan
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

    /** Quotes moving everything the channels can send. */
    fun quote() = manager.quote()

    fun move() = manager.move()

    override fun onCleared() = close()
}

@Immutable
sealed interface MoveToSparkUiState {
    data object Quoting : MoveToSparkUiState

    data class Confirm(val plan: MoveToSparkPlan, val isMoving: Boolean = false) : MoveToSparkUiState

    data class Done(val outcome: MoveToSparkOutcome) : MoveToSparkUiState

    data class Failed(val failure: MoveToSparkFailure) : MoveToSparkUiState
}

enum class MoveToSparkFailure { NOTHING_TO_MOVE, SPARK_UNAVAILABLE, UNKNOWN }
