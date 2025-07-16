package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.models.HealthState
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

@Singleton
class HealthRepo @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val connectivityRepo: ConnectivityRepo,
    private val lightningRepo: LightningRepo,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val _healthState = MutableStateFlow(AppHealthState())
    val healthState: StateFlow<AppHealthState> = _healthState.asStateFlow()

    init {
        observeNetworkConnectivity()
        observeLightningNodeState()
    }

    private fun observeNetworkConnectivity() {
        repoScope.launch {
            var lastState: ConnectivityState? = null

            connectivityRepo.isOnline.transform { newState ->
                    when { // Direct transitions that don't need minimum duration
                        newState == ConnectivityState.DISCONNECTED -> {
                            lastState = newState
                            emit(newState)
                        }

                        // DISCONNECTED → CONNECTED transition: show CONNECTING first
                        lastState == ConnectivityState.DISCONNECTED && newState == ConnectivityState.CONNECTED -> {
                            lastState = newState
                            emit(ConnectivityState.CONNECTING) // Show PENDING first
                            delay(1.5.seconds)
                            emit(ConnectivityState.CONNECTED)
                        }

                        // DISCONNECTED → CONNECTING transition: show it immediately
                        lastState == ConnectivityState.DISCONNECTED && newState == ConnectivityState.CONNECTING -> {
                            lastState = newState
                            emit(ConnectivityState.CONNECTING)
                            delay(1.5.seconds) // Don't emit again, wait for actual CONNECTED state
                        }

                        // CONNECTING → CONNECTED: emit if enough time has passed
                        lastState == ConnectivityState.CONNECTING && newState == ConnectivityState.CONNECTED -> {
                            lastState = newState
                            emit(newState)
                        }

                        // All other transitions: emit directly
                        else -> {
                            lastState = newState
                            emit(newState)
                        }
                    }
                }.collect { connectivityState ->
                    val internetState = when (connectivityState) {
                        ConnectivityState.CONNECTED -> HealthState.READY
                        ConnectivityState.CONNECTING -> HealthState.PENDING
                        ConnectivityState.DISCONNECTED -> HealthState.ERROR
                    }
                    updateState { it.copy(internetState = internetState) }
                }
        }
    }

    private fun observeLightningNodeState() {
        repoScope.launch {
            lightningRepo.lightningState.collect { lightningState ->
                val lightningNodeHealth = lightningState.nodeLifecycleState.asHealth()

                updateState { it.copy(lightningNodeState = lightningNodeHealth) }
            }
        }
    }

    private fun updateState(update: (AppHealthState) -> AppHealthState) {
        _healthState.update { currentState ->
            val updatedState = update(currentState)

            // Compute overall health from all individual states
            val states = listOf(
                updatedState.internetState,
                updatedState.bitcoinNodeState,
                updatedState.lightningNodeState,
                updatedState.backupState,
            )

            val overallHealth = when {
                HealthState.ERROR in states -> HealthState.ERROR
                HealthState.PENDING in states -> HealthState.PENDING
                else -> HealthState.READY
            }

            updatedState.copy(overallHealth = overallHealth)
        }
    }
}

data class AppHealthState(
    val internetState: HealthState = HealthState.READY,
    val bitcoinNodeState: HealthState = HealthState.READY,
    val lightningNodeState: HealthState = HealthState.READY,
    val lightningConnectionState: HealthState = HealthState.READY,
    val backupState: HealthState = HealthState.READY,
    val overallHealth: HealthState = HealthState.READY,
)
