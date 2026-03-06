package to.bitkit.ui.screens.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class PubkyRingAuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PubkyRingAuthUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkyRingAuthEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var approvalJob: Job? = null

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isWaitingForRing || _uiState.value.isAuthenticating) {
            approvalJob?.cancel()
            pubkyRepo.cancelAuthenticationSync()
        }
    }

    fun authenticate() {
        viewModelScope.launch {
            if (_uiState.value.isWaitingForRing) {
                approvalJob?.cancel()
                approvalJob = null
                _uiState.update { it.copy(isWaitingForRing = false) }
                pubkyRepo.cancelAuthentication()
            }

            _uiState.update { it.copy(isAuthenticating = true) }

            pubkyRepo.startAuthentication()
                .onSuccess { authUrl ->
                    _uiState.update { it.copy(isAuthenticating = false) }

                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    val canOpen = intent.resolveActivity(context.packageManager) != null
                    if (!canOpen) {
                        approvalJob?.cancel()
                        pubkyRepo.cancelAuthentication()
                        _uiState.update { it.copy(showRingNotInstalledDialog = true) }
                        return@launch
                    }

                    _uiState.update { it.copy(isWaitingForRing = true) }
                    context.startActivity(intent)
                }
                .onFailure {
                    _uiState.update { state -> state.copy(isAuthenticating = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                }
        }
    }

    fun waitForApproval() {
        if (approvalJob?.isActive == true) return

        approvalJob = viewModelScope.launch {
            pubkyRepo.completeAuthentication()
                .onSuccess {
                    _uiState.update { it.copy(isWaitingForRing = false) }
                    _effects.emit(PubkyRingAuthEffect.Authenticated)
                }
                .onFailure {
                    _uiState.update { state -> state.copy(isWaitingForRing = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                }
        }
    }

    fun dismissRingNotInstalledDialog() {
        _uiState.update { it.copy(showRingNotInstalledDialog = false) }
    }
}

data class PubkyRingAuthUiState(
    val isAuthenticating: Boolean = false,
    val isWaitingForRing: Boolean = false,
    val showRingNotInstalledDialog: Boolean = false,
)

sealed interface PubkyRingAuthEffect {
    data object Authenticated : PubkyRingAuthEffect
}
