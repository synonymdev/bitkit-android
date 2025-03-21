package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.LnPeer
import to.bitkit.models.Toast
import to.bitkit.services.LightningService
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.ResourceProvider
import to.bitkit.viewmodels.ExternalNodeContract.SideEffect
import to.bitkit.viewmodels.ExternalNodeContract.UiState
import javax.inject.Inject

@HiltViewModel
class ExternalNodeViewModel @Inject constructor(
    private val resourceProvider: ResourceProvider,
    private val lightningService: LightningService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<SideEffect>(replay = 0, extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()
    private fun setEffect(effect: SideEffect) = viewModelScope.launch { _effects.emit(effect) }

    fun onConnectionContinue(peer: LnPeer) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = lightningService.connectPeer(peer)

            _uiState.update { it.copy(isLoading = false) }

            if (result.isSuccess) {
                setEffect(SideEffect.ConnectionSuccess)
            } else {
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = resourceProvider.getString(R.string.lightning__error_add_title),
                    description = resourceProvider.getString(R.string.lightning__error_add),
                )
            }
        }
    }
}

// region contract
interface ExternalNodeContract {
    data class UiState(
        val isLoading: Boolean = false,
    )
    sealed class SideEffect {
        data object ConnectionSuccess : SideEffect()
    }
} // endregion
