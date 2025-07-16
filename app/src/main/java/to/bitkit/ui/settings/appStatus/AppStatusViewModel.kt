package to.bitkit.ui.settings.appStatus

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.repositories.ConnectivityRepo
import to.bitkit.repositories.ConnectivityState
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class AppStatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val connectivityRepo: ConnectivityRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppStatusUiState())
    val uiState: StateFlow<AppStatusUiState> = _uiState.asStateFlow()

    init {
        observeNetworkConnectivity()
    }

    private fun observeNetworkConnectivity() {
        viewModelScope.launch(bgDispatcher) {
            var lastState: ConnectivityState? = null

            connectivityRepo.isOnline
                .transform { newState ->
                    when {
                        // Direct transitions that don't need minimum duration
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
                            delay(1.5.seconds)
                            // Don't emit again, wait for actual CONNECTED state
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
                }
                .collect { connectivityState ->
                    val internetState = when (connectivityState) {
                        ConnectivityState.CONNECTED -> StatusUi.State.READY
                        ConnectivityState.CONNECTING -> StatusUi.State.PENDING
                        ConnectivityState.DISCONNECTED -> StatusUi.State.ERROR
                    }
                    updateInternetState(internetState)
                }
        }
    }

    private fun updateInternetState(newState: StatusUi.State) {
        _uiState.update { it.copy(internetState = newState) }
    }
}

data class AppStatusUiState(
    val internetState: StatusUi.State = StatusUi.State.READY,
    val bitcoinNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningConnectionState: StatusUi.State = StatusUi.State.READY,
    val backupState: StatusUi.State = StatusUi.State.READY,
)
