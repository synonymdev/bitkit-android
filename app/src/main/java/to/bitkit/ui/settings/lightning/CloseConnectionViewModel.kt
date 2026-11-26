package to.bitkit.ui.settings.lightning

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.LightningRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.usecases.CloseChannelUseCase
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class CloseConnectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lightningRepo: LightningRepo,
    private val closeChannelUseCase: CloseChannelUseCase,
) : ViewModel() {

    companion object {
        private const val TAG = "CloseConnectionViewModel"
    }

    private val _uiState = MutableStateFlow(CloseConnectionUiState())
    val uiState = _uiState.asStateFlow()

    fun closeChannel(channelId: String) {
        val channel = lightningRepo.lastKnownChannels.value
            .find { it.channelId == channelId }
            ?: run {
                Logger.error("No channel found for closing: '$channelId'", context = TAG)
                return
            }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            closeChannelUseCase(channel).fold(
                onSuccess = {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.lightning__close_success_title),
                        description = context.getString(R.string.lightning__close_success_msg),
                    )

                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            closeSuccess = true,
                        )
                    }
                },
                onFailure = {
                    Logger.error("Failed to close channel", it, context = TAG)

                    ToastEventBus.send(
                        type = Toast.ToastType.WARNING,
                        title = context.getString(R.string.lightning__close_error),
                        description = context.getString(R.string.lightning__close_error_msg),
                    )

                    _uiState.update { it.copy(isLoading = false) }
                }
            )
        }
    }
}

data class CloseConnectionUiState(
    val isLoading: Boolean = false,
    val closeSuccess: Boolean = false,
)
