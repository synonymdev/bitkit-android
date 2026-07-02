package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyAuthPermission
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class PubkyAuthApprovalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "PubkyAuthApprovalVM"
    }

    private val _uiState = MutableStateFlow(PubkyAuthApprovalUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkyAuthApprovalEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    fun load(authUrl: String) {
        viewModelScope.launch {
            val details = pubkyRepo.parseAuthUrl(authUrl).getOrElse {
                Logger.error("Failed to parse auth request", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__auth_error_title),
                    description = it.message,
                )
                _effects.emit(PubkyAuthApprovalEffect.Dismiss)
                return@launch
            }
            val caps = details.capabilities.orEmpty()
            val permissions = PubkyAuthRequest.parseCapabilities(caps)
            val serviceNames = permissions.mapNotNull { PubkyAuthRequest.extractServiceName(it.path) }.distinct()
            val unknownService = context.getString(R.string.profile__auth_approval_service_unknown)
            val serviceName = serviceNames.firstOrNull() ?: unknownService
            val profile = pubkyRepo.profile.value

            _uiState.update {
                it.copy(
                    state = ApprovalState.Authorize,
                    serviceName = serviceName,
                    requestedCapabilities = caps,
                    permissions = permissions.toImmutableList(),
                    profile = profile,
                )
            }
        }
    }

    fun requestAuthorize(authUrl: String) {
        viewModelScope.launch {
            _effects.emit(PubkyAuthApprovalEffect.RequestLocalAuth(authUrl))
        }
    }

    fun confirmAuthorize(authUrl: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(state = ApprovalState.Authorizing) }
            val capabilities = _uiState.value.requestedCapabilities.ifBlank {
                pubkyRepo.parseAuthUrl(authUrl).getOrElse {
                    Logger.error("Failed to parse auth request", it, context = TAG)
                    _uiState.update { state -> state.copy(state = ApprovalState.Authorize) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                    return@launch
                }.capabilities.orEmpty()
            }

            pubkyRepo.approveAuth(authUrl, capabilities)
                .onSuccess {
                    Logger.info("Auth approved for '${_uiState.value.serviceName}'", context = TAG)
                    _uiState.update { it.copy(state = ApprovalState.Success) }
                }
                .onFailure {
                    Logger.error("Auth approval failed", it, context = TAG)
                    _uiState.update { it.copy(state = ApprovalState.Authorize) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                }
        }
    }

    fun dismiss() {
        viewModelScope.launch { _effects.emit(PubkyAuthApprovalEffect.Dismiss) }
    }
}

@Stable
data class PubkyAuthApprovalUiState(
    val state: ApprovalState = ApprovalState.Loading,
    val serviceName: String = "",
    val requestedCapabilities: String = "",
    val permissions: ImmutableList<PubkyAuthPermission> = persistentListOf(),
    val profile: PubkyProfile? = null,
)

sealed interface ApprovalState {
    data object Loading : ApprovalState
    data object Authorize : ApprovalState
    data object Authorizing : ApprovalState
    data object Success : ApprovalState
}

sealed interface PubkyAuthApprovalEffect {
    data class RequestLocalAuth(val authUrl: String) : PubkyAuthApprovalEffect
    data object Dismiss : PubkyAuthApprovalEffect
}
