package to.bitkit.ui.settings.lightning

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lightningdevkit.ldknode.Network
import to.bitkit.di.BgDispatcher
import to.bitkit.env.Env
import to.bitkit.models.HealthState
import to.bitkit.repositories.HealthRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.repositories.SparkExitRepo
import to.bitkit.repositories.SparkExitState
import to.bitkit.repositories.SparkRepo
import to.bitkit.repositories.SparkState
import to.bitkit.ui.settings.appStatus.ExitStep
import to.bitkit.ui.settings.appStatus.exitStep
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

@HiltViewModel
class SparkDetailViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val lightningRepo: LightningRepo,
    private val sparkRepo: SparkRepo,
    private val sparkExitRepo: SparkExitRepo,
    private val healthRepo: HealthRepo,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SparkDetailUiState())
    val uiState = _uiState.asStateFlow()

    init {
        viewModelScope.launch(bgDispatcher) {
            combine(sparkRepo.state, sparkExitRepo.state, isOnline()) { state, exit, isOnline ->
                Triple(state, exit, isOnline)
            }.collect { (state, exit, isOnline) ->
                _uiState.update {
                    it.copy(
                        spark = state.toDetailUi(exit, isOnline),
                        exitStep = exit.record.exitStep(exit.fundingState),
                    )
                }
            }
        }
        viewModelScope.launch(bgDispatcher) {
            healthRepo.healthState.collect { health ->
                _uiState.update {
                    it.copy(
                        status = health.spark?.let { s -> if (health.isSparkExiting) HealthState.PENDING else s }
                            ?: HealthState.ERROR,
                    )
                }
            }
        }
        viewModelScope.launch(bgDispatcher) {
            // A cancelled exit's address may still receive a late payment: its balance shows here.
            sparkExitRepo.refreshEarlierAddresses()
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

    private fun isOnline() = healthRepo.healthState.map { it.internet == HealthState.READY }.distinctUntilChanged()

    fun onPullToRefresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            sparkRepo.refreshState()
            sparkExitRepo.refreshEarlierAddresses()
            val exit = sparkExitRepo.state.value
            _uiState.update {
                it.copy(
                    spark = sparkRepo.state.value.toDetailUi(
                        exit,
                        healthRepo.healthState.value.internet == HealthState.READY,
                    )
                )
            }
            delay(500.milliseconds)
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /** An empty Spark is left out of the connections list, yet still receives here. */
    private suspend fun SparkState.toDetailUi(exit: SparkExitState, isOnline: Boolean) =
        toUi(lightningRepo, exit.showsResult, exit.earlierAddresses, isOnline)
            ?: SparkUi(balanceSats = 0uL, isReceiving = isReceiving(isOnline))
}

@Immutable
data class SparkDetailUiState(
    val spark: SparkUi? = null,
    val isRefreshing: Boolean = false,
    /** Spark's connection, as App Status shows it. */
    val status: HealthState = HealthState.PENDING,
    /** A running force transfer to savings: the status reads as App Status's Spark row does. */
    val exitStep: ExitStep? = null,
    /** Spark's on-chain deposit address, shown on regtest to fund Spark from Lightspark's regtest faucet. */
    val depositAddress: String? = null,
)
