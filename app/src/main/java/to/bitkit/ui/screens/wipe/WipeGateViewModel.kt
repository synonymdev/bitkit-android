package to.bitkit.ui.screens.wipe

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.LogsRepo
import to.bitkit.security.WipeCoordinator
import to.bitkit.security.WipeState
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class WipeGateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wipeCoordinator: WipeCoordinator,
    private val logsRepo: LogsRepo,
) : ViewModel() {
    val uiState: StateFlow<WipeGateUiState> = wipeCoordinator.state
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), wipeCoordinator.state.value.toUiState())

    fun onRetry() = wipeCoordinator.retry()

    fun zipLogsForSharing(onReady: (Uri) -> Unit) {
        viewModelScope.launch {
            logsRepo.zipLogsForSharing()
                .onSuccess { onReady(it) }
                .onFailure {
                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__error_logs),
                        description = context.getString(R.string.lightning__error_logs_description),
                    )
                }
        }
    }

    private fun WipeState.toUiState() = when (this) {
        is WipeState.Failed -> WipeGateUiState(mode = WipeGateMode.FAILED, failedStep = step?.name)
        is WipeState.Blocked -> WipeGateUiState(mode = WipeGateMode.BLOCKED)
        WipeState.Idle, WipeState.Running -> WipeGateUiState(mode = WipeGateMode.RUNNING)
    }
}

@Immutable
data class WipeGateUiState(
    val mode: WipeGateMode = WipeGateMode.RUNNING,
    val failedStep: String? = null,
)

enum class WipeGateMode { RUNNING, FAILED, BLOCKED }
