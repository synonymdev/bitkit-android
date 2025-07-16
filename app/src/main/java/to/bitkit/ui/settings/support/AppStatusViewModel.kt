package to.bitkit.ui.settings.support

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.repositories.ConnectivityRepo
import javax.inject.Inject

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
            connectivityRepo.isOnline.collect { isConnected ->
                val internetState = if (isConnected) StatusUi.State.READY else StatusUi.State.ERROR
                _uiState.update { it.copy(internetState = internetState) }
            }
        }
    }
}

data class AppStatusUiState(
    val internetState: StatusUi.State = StatusUi.State.READY,
    val bitcoinNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningConnectionState: StatusUi.State = StatusUi.State.PENDING,
    val backupState: StatusUi.State = StatusUi.State.ERROR,
)
