package to.bitkit.repositories

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    private val blocktankRepo: BlocktankRepo,
) {
    private val repoScope = CoroutineScope(bgDispatcher + SupervisorJob())

    private val _healthState = MutableStateFlow(AppHealthState())
    val healthState: StateFlow<AppHealthState> = _healthState.asStateFlow()

    init {
        observeNetworkConnectivity()
        observeLightningNodeState()
        observePaidOrdersState()
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
                updateState { it.copy(internet = internetState) }
            }
        }
    }

    private fun observeLightningNodeState() {
        repoScope.launch {
            lightningRepo.lightningState.collect { lightningState ->
                val nodeLifecycleState = lightningState.nodeLifecycleState

                updateState { currentState ->
                    val nodeStatus = when {
                        !currentState.isOnline() -> HealthState.ERROR
                        else -> nodeLifecycleState.asHealth()
                    }

                    val electrumStatus = when {
                        !currentState.isOnline() -> HealthState.ERROR
                        nodeLifecycleState.isRunning() -> HealthState.READY
                        nodeLifecycleState.canRun() -> HealthState.PENDING
                        else -> HealthState.ERROR
                    }

                    val channelsStatus = when {
                        !currentState.isOnline() -> HealthState.ERROR
                        else -> {
                            val channels = lightningState.channels
                            val hasOpenChannels = channels.any { it.isChannelReady }
                            val hasPendingChannels = channels.any { !it.isChannelReady }

                            when {
                                hasOpenChannels -> HealthState.READY
                                hasPendingChannels -> HealthState.PENDING
                                else -> HealthState.ERROR
                            }
                        }
                    }

                    currentState.copy(
                        node = nodeStatus,
                        electrum = electrumStatus,
                        channels = channelsStatus,
                    )
                }
            }
        }
    }

    private fun observePaidOrdersState() {
        repoScope.launch {
            blocktankRepo.blocktankState.map { it.paidOrders }.distinctUntilChanged().collect { paidOrders ->
                if (paidOrders.isNotEmpty()) {
                    updateState { currentState ->
                        // only override lightning connection to PENDING if it's currently ERROR
                        val channelsStatus = when (currentState.channels) {
                            HealthState.ERROR -> HealthState.PENDING
                            else -> currentState.channels
                        }

                        currentState.copy(channels = channelsStatus)
                    }
                }
            }
        }
    }

    private fun updateState(update: (AppHealthState) -> AppHealthState) {
        _healthState.update { currentState ->
            val updatedState = update(currentState)

            // Compute app health status from all individual states
            val states = listOf(
                updatedState.internet,
                updatedState.electrum,
                updatedState.node,
                updatedState.backups,
            )

            val appStatus = when {
                HealthState.ERROR in states -> HealthState.ERROR
                HealthState.PENDING in states -> HealthState.PENDING
                else -> HealthState.READY
            }

            updatedState.copy(app = appStatus)
        }
    }
}

data class AppHealthState(
    val internet: HealthState = HealthState.READY,
    val electrum: HealthState = HealthState.READY,
    val node: HealthState = HealthState.READY,
    val channels: HealthState = HealthState.READY,
    val backups: HealthState = HealthState.READY,
    val app: HealthState = HealthState.READY,
) {
    fun isOnline() = internet == HealthState.READY
}
