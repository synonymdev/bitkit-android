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
import to.bitkit.models.PubkyAuthRequest
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.WatchOnlyAccountAuthorizationStartError
import to.bitkit.repositories.WatchOnlyAccountRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.ui.utils.localizedPubkyAuthMessage
import to.bitkit.utils.Logger
import java.util.concurrent.atomic.AtomicReference
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

    private val inFlightAuthorization = AtomicReference<InFlightAuthorization?>()
    private val accountIdsAwaitingLocalActivation = mutableMapOf<String, String>()

    fun load(authUrl: String) {
        inFlightAuthorization.get()?.takeIf { it.authUrl == authUrl }?.let { authorization ->
            if (_uiState.value.authUrl != authUrl) {
                authorization.uiState?.let { authorizingState ->
                    _uiState.update { authorizingState }
                }
            }
            return
        }
        if (!resetForLoad(authUrl)) return
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
        val authorization = InFlightAuthorization(authUrl)
        if (!inFlightAuthorization.compareAndSet(null, authorization)) return
        val authorizingState = transitionToAuthorizing(authUrl)
        if (authorizingState == null) {
            inFlightAuthorization.compareAndSet(authorization, null)
            return
        }
        authorization.uiState = authorizingState

        viewModelScope.launch {
            try {
                authorize(authUrl, authorizingState.watchOnlyAccountName)
            } finally {
                inFlightAuthorization.compareAndSet(authorization, null)
            }
        }
    }

    private suspend fun authorize(authUrl: String, watchOnlyAccountName: String) {
        val request = pubkyRepo.parseAuthUrl(authUrl).getOrElse {
            handleApprovalFailure(it, authUrl)
            return
        }
        if (_uiState.value.authUrl != authUrl) return
        if (!approveRequest(request, authUrl, watchOnlyAccountName)) return

        Logger.info("Auth approved for '${request.serviceNames.firstOrNull().orEmpty()}'", context = TAG)
        _uiState.update { state ->
            if (state.authUrl == authUrl) state.copy(state = ApprovalState.Success) else state
        }
    }

    private suspend fun approveRequest(
        request: PubkyAuthRequest,
        authUrl: String,
        watchOnlyAccountName: String,
    ): Boolean = resumeLocalActivation(authUrl) ?: approveNewRequest(request, authUrl, watchOnlyAccountName)

    private suspend fun approveNewRequest(
        request: PubkyAuthRequest,
        authUrl: String,
        watchOnlyAccountName: String,
    ): Boolean {
        val preparedClaim = runSuspendCatching {
            if (request.bitkitClaim == PubkyAuthClaim.WATCH_ONLY_ACCOUNT_V1) {
                watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, watchOnlyAccountName)
            } else {
                null
            }
        }.getOrElse {
            handleApprovalFailure(it, authUrl)
            return false
        }

        var preserveAuthorizingState = preparedClaim?.account?.setupState == WatchOnlyAccountSetupState.Authorizing
        preparedClaim?.let { claim ->
            runSuspendCatching { watchOnlyAccountRepo.beginAuthorization(claim.account.id) }
                .onSuccess { preserveAuthorizingState = it }
                .getOrElse {
                    if (it is WatchOnlyAccountAuthorizationStartError) {
                        preserveAuthorizingState = it.preserveAuthorizingState
                    }
                    cancelIncompleteSetup(claim.account.id, preserveAuthorizingState)
                    handleApprovalFailure(it, authUrl)
                    return false
                }
        }

        val approvalResult = preparedClaim?.let {
            pubkyRepo.approveAuthWithCompanionClaim(authUrl, it.payload)
        } ?: pubkyRepo.approveAuth(authUrl, request.capabilities)
        if (approvalResult.isFailure) {
            val approvalError = approvalResult.exceptionOrNull() ?: IllegalStateException("Authorization failed")
            preparedClaim?.let { claim ->
                if (!approvalError.isPostDeliveryAuthorizationFailure()) {
                    cancelIncompleteSetup(
                        claim.account.id,
                        preserveAuthorizingState,
                    )
                }
            }
            handleApprovalFailure(approvalError, authUrl)
            return false
        }

        preparedClaim?.let { claim ->
            accountIdsAwaitingLocalActivation[authUrl] = claim.account.id
            runSuspendCatching { watchOnlyAccountRepo.markActive(claim.account.id) }.getOrElse {
                handleApprovalFailure(it, authUrl)
                return false
            }
            accountIdsAwaitingLocalActivation.remove(authUrl, claim.account.id)
        }
        return true
    }

    private suspend fun resumeLocalActivation(authUrl: String): Boolean? {
        val accountId = accountIdsAwaitingLocalActivation[authUrl] ?: return null
        val result = runSuspendCatching { watchOnlyAccountRepo.markActive(accountId) }
        if (result.isSuccess) {
            accountIdsAwaitingLocalActivation.remove(authUrl, accountId)
            return true
        }
        handleApprovalFailure(result.exceptionOrNull() ?: IllegalStateException("Account activation failed"), authUrl)
        return false
    }

    private fun transitionToAuthorizing(authUrl: String): PubkyAuthApprovalUiState? {
        val initialState = _uiState.value
        if (initialState.authUrl != authUrl || initialState.state != ApprovalState.Authorize) return null
        val authorizingState = initialState.copy(state = ApprovalState.Authorizing)
        return authorizingState.takeIf { _uiState.compareAndSet(initialState, it) }
    }

    private fun resetForLoad(authUrl: String): Boolean {
        while (true) {
            val currentState = _uiState.value
            if (currentState.authUrl == authUrl && currentState.state == ApprovalState.Authorizing) return false
            if (_uiState.compareAndSet(currentState, PubkyAuthApprovalUiState(authUrl = authUrl))) return true
        }
    }

    private suspend fun cancelIncompleteSetup(
        accountId: String,
        preserveAuthorizingState: Boolean,
    ) {
        runSuspendCatching {
            watchOnlyAccountRepo.cancelAuthorization(accountId, preserveAuthorizingState)
        }
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

private class InFlightAuthorization(
    val authUrl: String,
) {
    @Volatile
    var uiState: PubkyAuthApprovalUiState? = null
}

private fun Throwable.isPostDeliveryAuthorizationFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is PubkyAuthCompanionClaimApprovalException.AuthorizationFailure) return true
        current = current.cause
    }
    return false
}
