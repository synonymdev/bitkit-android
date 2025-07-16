package to.bitkit.ui.settings.support

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import to.bitkit.di.BgDispatcher
import javax.inject.Inject

@HiltViewModel
class AppStatusViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppStatusUiState())
    val uiState: StateFlow<AppStatusUiState> = _uiState.asStateFlow()
}

data class AppStatusUiState(
    val internetState: StatusUi.State = StatusUi.State.READY,
    val bitcoinNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningNodeState: StatusUi.State = StatusUi.State.READY,
    val lightningConnectionState: StatusUi.State = StatusUi.State.PENDING,
    val backupState: StatusUi.State = StatusUi.State.ERROR,
)
