package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.paykit.PubkyAuthCompanionClaimApprovalException
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
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.PubkyAuthClaim
import to.bitkit.models.PubkyAuthPermission
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.utils.localizedPubkyAuthMessage
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
        _uiState.value = PubkyAuthApprovalUiState(authUrl = authUrl)
        viewModelScope.launch {
            val request = pubkyRepo.parseAuthUrl(authUrl).getOrElse {
                if (_uiState.value.authUrl != authUrl) return@launch
                Logger.error("Failed to parse auth request", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__auth_error_title),
                    description = it.localizedPubkyAuthMessage(context),
                )
                _effects.emit(PubkyAuthApprovalEffect.Dismiss)
                return@launch
            }
            if (_uiState.value.authUrl != authUrl) return@launch
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
        val state = _uiState.value
        if (state.authUrl != authUrl || state.state != ApprovalState.Authorize) return
        viewModelScope.launch {
            _effects.emit(PubkyAuthApprovalEffect.RequestLocalAuth(authUrl))
        }
    }

    fun updateWatchOnlyAccountName(name: String) {
        _uiState.update { it.copy(watchOnlyAccountName = name) }
    }

    fun confirmAuthorize(authUrl: String) {
        if (!transitionToAuthorizing(authUrl)) return

        viewModelScope.launch {
            val request = pubkyRepo.parseAuthUrl(authUrl).getOrElse {
                handleApprovalFailure(it, authUrl)
                return@launch
            }
            if (_uiState.value.authUrl != authUrl) return@launch

            val preparedClaim = runSuspendCatching {
                if (request.bitkitClaim == PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1) {
                    watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, _uiState.value.watchOnlyAccountName)
                } else {
                    null
                }
            }.getOrElse {
                handleApprovalFailure(it, authUrl)
                return@launch
            }

            preparedClaim?.let { claim ->
                runSuspendCatching { watchOnlyAccountRepo.beginAuthorization(claim.account.id) }.getOrElse {
                    cancelIncompleteSetup(claim.account.id)
                    handleApprovalFailure(it, authUrl)
                    return@launch
                }
            }

            val approvalResult = preparedClaim?.let {
                pubkyRepo.approveAuthWithCompanionClaim(authUrl, it.payload)
            } ?: pubkyRepo.approveAuth(authUrl, request.capabilities)
            if (approvalResult.isFailure) {
                val approvalError = approvalResult.exceptionOrNull() ?: IllegalStateException("Authorization failed")
                preparedClaim?.let { claim ->
                    if (!approvalError.isPostDeliveryAuthorizationFailure()) {
                        cancelIncompleteSetup(claim.account.id)
                    }
                }
                handleApprovalFailure(approvalError, authUrl)
                return@launch
            }

            preparedClaim?.let { claim ->
                runSuspendCatching { watchOnlyAccountRepo.markActive(claim.account.id) }.getOrElse {
                    handleApprovalFailure(it, authUrl)
                    return@launch
                }
            }
            Logger.info("Auth approved for '${request.serviceNames.firstOrNull().orEmpty()}'", context = TAG)
            _uiState.update { state ->
                if (state.authUrl == authUrl) state.copy(state = ApprovalState.Success) else state
            }
        }
    }

    private fun transitionToAuthorizing(authUrl: String): Boolean {
        val initialState = _uiState.value
        if (initialState.authUrl != authUrl || initialState.state != ApprovalState.Authorize) return false
        return _uiState.compareAndSet(initialState, initialState.copy(state = ApprovalState.Authorizing))
    }

    private suspend fun cancelIncompleteSetup(accountId: String) {
        runSuspendCatching { watchOnlyAccountRepo.cancelAuthorization(accountId) }
            .onFailure {
                Logger.error(
                    "Failed to unload incomplete watch-only account",
                    it,
                    context = TAG,
                )
            }
    }

    private suspend fun handleApprovalFailure(error: Throwable, authUrl: String) {
        Logger.error("Auth approval failed", error, context = TAG)
        if (_uiState.value.authUrl != authUrl) return
        _uiState.update { it.copy(state = ApprovalState.Authorize) }
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.profile__auth_error_title),
            description = error.localizedPubkyAuthMessage(context),
        )
    }

    fun dismiss() {
        viewModelScope.launch { _effects.emit(PubkyAuthApprovalEffect.Dismiss) }
    }
}

@Stable
data class PubkyAuthApprovalUiState(
    val authUrl: String = "",
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

private fun Throwable.isPostDeliveryAuthorizationFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is PubkyAuthCompanionClaimApprovalException.AuthorizationFailure) return true
        current = current.cause
    }
    return false
}
