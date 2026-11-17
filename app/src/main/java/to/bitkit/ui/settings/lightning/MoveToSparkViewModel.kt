package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkQuote
import javax.inject.Inject

/** The Move to Spark screen's view of [MoveToSparkManager], which keeps a move running after the screen closes. */
@HiltViewModel
class MoveToSparkViewModel @Inject constructor(
    private val manager: MoveToSparkManager,
) : ViewModel() {

    val uiState = manager.uiState

    init {
        manager.start()
    }

    /** Quotes moving [targetSats], or everything the channels can send. */
    fun quote(targetSats: ULong? = null) = manager.quote(targetSats)

    fun move() = manager.move()

    override fun onCleared() {
        manager.reset()
    }
}

@Immutable
sealed interface MoveToSparkUiState {
    data object Quoting : MoveToSparkUiState

    data class Confirm(val quote: MoveToSparkQuote, val isMoving: Boolean = false) : MoveToSparkUiState

    /** [hasRest]: the channels can still send enough to move again, as after a smaller amount. */
    data class Done(
        val outcome: MoveToSparkOutcome,
        val closeChannelId: String?,
        val hasRest: Boolean = false,
    ) : MoveToSparkUiState

    /** [amountSats] did not route in one payment; [smallerSats] is the next amount to try, if any is left. */
    data class TooLarge(val amountSats: ULong, val smallerSats: ULong?) : MoveToSparkUiState

    data class Failed(val failure: MoveToSparkFailure) : MoveToSparkUiState
}

enum class MoveToSparkFailure { NOTHING_TO_MOVE, SPARK_UNAVAILABLE, UNKNOWN }
