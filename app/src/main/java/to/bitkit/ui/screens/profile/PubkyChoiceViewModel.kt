package to.bitkit.ui.screens.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Immutable
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
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class PubkyChoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "PubkyChoiceViewModel"
    }

    private val _uiState = MutableStateFlow(PubkyChoiceUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkyChoiceEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var approvalJob: Job? = null

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isWaitingForRing) {
            pubkyRepo.cancelAuthenticationSync()
        }
    }

    fun startRingAuth() {
        viewModelScope.launch {
            if (_uiState.value.isWaitingForRing) {
                approvalJob?.cancel()
                approvalJob = null
                _uiState.update { it.copy(isWaitingForRing = false) }
                pubkyRepo.cancelAuthentication()
            }

            pubkyRepo.startAuthentication()
                .onSuccess { authUrl ->
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
                    _effects.emit(PubkyChoiceEffect.OpenRingAuth(authUrl))
                    waitForApproval()
                }
                .onFailure {
                    Logger.error("Starting Ring auth failed", it, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                }
        }
    }

    private fun waitForApproval() {
        if (approvalJob?.isActive == true) return

        approvalJob = viewModelScope.launch {
            pubkyRepo.completeAuthentication()
                .onSuccess {
                    _uiState.update { it.copy(isWaitingForRing = false, isLoadingAfterAuth = true) }
                    pubkyRepo.prepareImport()
                    _uiState.update { it.copy(isLoadingAfterAuth = false) }
                    val hasContacts = pubkyRepo.pendingImportContacts.value.isNotEmpty()
                    if (hasContacts) {
                        _effects.emit(PubkyChoiceEffect.NavigateToContactImportOverview)
                    } else {
                        _effects.emit(PubkyChoiceEffect.NavigateToPayContacts)
                    }
                }
                .onFailure {
                    Logger.error("Auth approval failed", it, context = TAG)
                    _uiState.update { it.copy(isWaitingForRing = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                }
        }
    }

    fun cancelAuth() {
        viewModelScope.launch {
            approvalJob?.cancel()
            approvalJob = null
            pubkyRepo.cancelAuthentication()
            _uiState.update { it.copy(isWaitingForRing = false, isLoadingAfterAuth = false) }
        }
    }

    fun dismissRingNotInstalledDialog() {
        _uiState.update { it.copy(showRingNotInstalledDialog = false) }
    }
}

@Immutable
data class PubkyChoiceUiState(
    val isWaitingForRing: Boolean = false,
    val isLoadingAfterAuth: Boolean = false,
    val showRingNotInstalledDialog: Boolean = false,
)

sealed interface PubkyChoiceEffect {
    data class OpenRingAuth(val authUrl: String) : PubkyChoiceEffect
    data object NavigateToCreateProfile : PubkyChoiceEffect
    data object NavigateToContactImportOverview : PubkyChoiceEffect
    data object NavigateToPayContacts : PubkyChoiceEffect
}
