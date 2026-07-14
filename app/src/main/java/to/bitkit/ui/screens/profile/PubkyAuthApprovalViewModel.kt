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
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthPermission
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class PubkyAuthApprovalViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val watchOnlyAccountRepo: WatchOnlyAccountRepo,
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
            val request = pubkyRepo.parseAuthUrl(authUrl).getOrElse {
                Logger.error("Failed to parse auth request", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__auth_error_title),
                    description = it.message,
                )
                _effects.emit(PubkyAuthApprovalEffect.Dismiss)
                return@launch
            }
            val unknownService = context.getString(R.string.profile__auth_approval_service_unknown)
            val serviceName = request.serviceNames.firstOrNull() ?: unknownService
            val profile = pubkyRepo.profile.value
            val accountName = if (request.bitkitClaim == PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1) {
                context.getString(R.string.profile__auth_approval_watch_only_account_default_name, serviceName)
            } else {
                ""
            }

            _uiState.update {
                it.copy(
                    state = ApprovalState.Authorize,
                    serviceName = serviceName,
                    requestedCapabilities = request.capabilities,
                    permissions = request.permissions.toImmutableList(),
                    bitkitClaim = request.bitkitClaim,
                    watchOnlyAccountName = accountName,
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

    fun updateWatchOnlyAccountName(name: String) {
        _uiState.update { it.copy(watchOnlyAccountName = name) }
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
                }.capabilities
            }

            val preparedClaim = runCatching {
                if (_uiState.value.bitkitClaim == PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1) {
                    watchOnlyAccountRepo.prepareSignedClaim(authUrl, _uiState.value.watchOnlyAccountName).also {
                        watchOnlyAccountRepo.deliver(it, authUrl)
                    }
                } else {
                    null
                }
            }.getOrElse {
                handleApprovalFailure(it)
                return@launch
            }

            val approvalResult = pubkyRepo.approveAuth(authUrl, capabilities)
            if (approvalResult.isFailure) {
                handleApprovalFailure(approvalResult.exceptionOrNull() ?: IllegalStateException("Authorization failed"))
                return@launch
            }

            preparedClaim?.let { claim ->
                runCatching { watchOnlyAccountRepo.markActive(claim.account.id) }
                    .onFailure { Logger.error("Failed to mark watch-only account active", it, context = TAG) }
            }
            Logger.info("Auth approved for '${_uiState.value.serviceName}'", context = TAG)
            _uiState.update { it.copy(state = ApprovalState.Success) }
        }
    }

    private suspend fun handleApprovalFailure(error: Throwable) {
        Logger.error("Auth approval failed", error, context = TAG)
        _uiState.update { it.copy(state = ApprovalState.Authorize) }
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.profile__auth_error_title),
            description = error.message,
        )
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
    val bitkitClaim: PubkyAuthClaim? = null,
    val watchOnlyAccountName: String = "",
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
