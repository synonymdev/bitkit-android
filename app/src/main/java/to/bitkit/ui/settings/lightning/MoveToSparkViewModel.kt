package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.ext.filterOpen
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.MoveToSparkError
import to.bitkit.repositories.MoveToSparkMath
import to.bitkit.repositories.MoveToSparkOutcome
import to.bitkit.repositories.MoveToSparkQuote
import to.bitkit.repositories.MoveToSparkRepo
import to.bitkit.repositories.WalletRepo
import javax.inject.Inject

@HiltViewModel
class MoveToSparkViewModel @Inject constructor(
    private val moveToSparkRepo: MoveToSparkRepo,
    private val lightningRepo: LightningRepo,
    private val walletRepo: WalletRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow<MoveToSparkUiState>(MoveToSparkUiState.Quoting)
    val uiState = _uiState.asStateFlow()

    init {
        quote()
    }

    /** Quotes moving [targetSats], or everything the channels can send. */
    fun quote(targetSats: ULong? = null) {
        _uiState.update { MoveToSparkUiState.Quoting }
        viewModelScope.launch {
            moveToSparkRepo.quote(targetSats).fold(
                onSuccess = { quote -> _uiState.update { MoveToSparkUiState.Confirm(quote) } },
                onFailure = { error -> _uiState.update { error.toUiState() } },
            )
        }
    }

    fun move() {
        val quote = (_uiState.value as? MoveToSparkUiState.Confirm)?.quote ?: return
        _uiState.update { MoveToSparkUiState.Confirm(quote, isMoving = true) }
        viewModelScope.launch {
            moveToSparkRepo.move(quote).fold(
                onSuccess = { outcome ->
                    walletRepo.syncNodeAndWallet()
                    val hasRest = moveToSparkRepo.movableSats() >= MoveToSparkMath.MIN_MOVE_SATS
                    _uiState.update { MoveToSparkUiState.Done(outcome, emptiedChannelId(), hasRest) }
                },
                onFailure = { error -> _uiState.update { error.toUiState() } },
            )
        }
    }

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
