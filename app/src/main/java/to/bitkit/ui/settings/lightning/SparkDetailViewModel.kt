package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Network
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.SparkRepo
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class SparkDetailViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val sparkRepo: SparkRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SparkDetailUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch(bgDispatcher) {
            sparkRepo.state.collect { state -> _uiState.update { it.copy(spark = state.toUi(lightningRepo)) } }
        }
        if (Env.network == Network.REGTEST) {
            viewModelScope.launch(bgDispatcher) {
                sparkRepo.depositAddress().onSuccess { address ->
                    _uiState.update {
                        it.copy(
                            depositAddress = address
                        )
                    }
                }
            }
        }
    }

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            sparkRepo.refreshState()
            _uiState.update { it.copy(spark = sparkRepo.state.value.toUi(lightningRepo)) }
            delay(500.milliseconds)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }
}

@Immutable
data class SparkDetailUiState(
    val spark: SparkUi? = null,
    val isRefreshing: Boolean = false,
    /** Spark's on-chain deposit address, shown on regtest to fund Spark from Lightspark's regtest faucet. */
    val depositAddress: String? = null,
)
