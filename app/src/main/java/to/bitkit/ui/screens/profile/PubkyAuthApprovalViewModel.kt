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
import to.bitkit.models.PubkyAuthRequestError
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.models.WatchOnlyAccountSetupState
import to.bitkit.repositories.Endpoint
import to.bitkit.repositories.PubkyAlreadySignedInError
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.UsdtRepo
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
    private val usdtRepo: UsdtRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "PubkyAuthApprovalVM"
    }

    private val _uiState = MutableStateFlow(PubkyAuthApprovalUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkyAuthApprovalEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private val inFlightAuthorization = AtomicReference<InFlightAuthorization?>()
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
            val serviceName = request.serviceNames.takeIf {
                it.isNotEmpty()
            }?.joinToString(context.getString(R.string.profile__auth_approval_services_separator)) ?: unknownService
            _uiState.update {
                it.copy(
                    state = if (request.bitkitClaim?.sharesReceivingDetails == true) {
                        ApprovalState.WatchOnlyConsent
                    } else {
                        ApprovalState.Authorize
                    },
                    clientId = request.clientId,
                    homeserverPublicKey = request.homeserverPublicKey,
                    createsIdentity = request.requiresIdentityCreation(pubkyRepo.publicKey.value != null),
                    serviceName = serviceName,
                    permissions = request.permissions.toImmutableList(),
                    bitkitClaim = request.bitkitClaim,
                    profile = approvalProfile(),
                )
            }
            loadUsdtAddress(authUrl)
        }
    }

    fun setShareUsdt(enabled: Boolean) {
        if (_uiState.value.state != ApprovalState.Authorize) return
        _uiState.update { it.copy(shareUsdt = enabled) }
    }

    fun loadUsdtAddress(authUrl: String) {
        if (_uiState.value.authUrl != authUrl || _uiState.value.bitkitClaim?.sharesUsdt != true) return
        _uiState.update { it.copy(usdtAddress = null, usdtUnavailable = false) }
        viewModelScope.launch {
            val address = usdtRepo.paymentEndpoint().getOrNull()?.value
            _uiState.update {
                if (it.authUrl == authUrl) {
                    it.copy(
                        usdtAddress = address,
                        usdtUnavailable = address == null,
                        shareUsdt = it.shareUsdt && address != null,
                    )
                } else {
                    it
                }
            }
        }
    }

    fun requestAuthorize(authUrl: String) {
        val state = _uiState.value
        if (state.authUrl != authUrl || state.state != ApprovalState.Authorize || !state.canAuthorize) return
        if (!_uiState.compareAndSet(state, state.copy(state = ApprovalState.Authenticating))) return
        viewModelScope.launch {
            _effects.emit(PubkyAuthApprovalEffect.RequestLocalAuth(authUrl))
        }
    }

    fun approveWatchOnlyConsent(authUrl: String) {
        _uiState.update { state ->
            if (state.authUrl == authUrl && state.state == ApprovalState.WatchOnlyConsent) {
                state.copy(state = ApprovalState.Authorize)
            } else {
                state
            }
        }
    }

    fun returnToWatchOnlyConsent(authUrl: String) {
        _uiState.update { state ->
            if (
                state.authUrl == authUrl &&
                state.state == ApprovalState.Authorize &&
                state.bitkitClaim?.sharesReceivingDetails == true
            ) {
                state.copy(state = ApprovalState.WatchOnlyConsent)
            } else {
                state
            }
        }
    }

    fun cancelLocalAuth(authUrl: String) {
        _uiState.update { state ->
            if (state.authUrl == authUrl && state.state == ApprovalState.Authenticating) {
                state.copy(state = ApprovalState.Authorize)
            } else {
                state
            }
        }
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
                authorize(authUrl)
            } finally {
                inFlightAuthorization.compareAndSet(authorization, null)
            }
        }
    }

    private suspend fun authorize(authUrl: String) {
        val request = pubkyRepo.parseAuthUrl(authUrl).getOrElse {
            handleApprovalFailure(it, authUrl)
            return
        }
        val approvalState = _uiState.value
        if (approvalState.authUrl != authUrl) return
        if (request.bitkitClaim != approvalState.bitkitClaim || request.clientId != approvalState.clientId ||
            request.permissions != approvalState.permissions
        ) {
            handleApprovalFailure(PubkyAuthRequestError.RequesterChanged, authUrl)
            return
        }
        if (!approveRequest(request, authUrl, approvalState.createsIdentity)) return

        Logger.info("Auth approved for '${request.serviceNames.firstOrNull().orEmpty()}'", context = TAG)
        if (approvalState.createsIdentity) {
            _effects.emit(PubkyAuthApprovalEffect.Dismiss)
            return
        }
        _uiState.update { state ->
            if (state.authUrl == authUrl) state.copy(state = ApprovalState.Success) else state
        }
    }

    private suspend fun approveRequest(
        request: PubkyAuthRequest,
        authUrl: String,
        createsIdentity: Boolean,
    ): Boolean = if (createsIdentity) {
        pubkyRepo.approveSignupAuth(request).fold(
            onSuccess = { true },
            onFailure = {
                handleApprovalFailure(it, authUrl)
                false
            },
        )
    } else {
        approveSignInRequest(request, authUrl)
    }

    private suspend fun approveSignInRequest(
        request: PubkyAuthRequest,
        authUrl: String,
    ): Boolean {
        val endpoint = runSuspendCatching {
            validatedUsdtEndpoint(request)
        }.getOrElse {
            handleApprovalFailure(it, authUrl)
            loadUsdtAddress(authUrl)
            return false
        }
        val preparedClaim = runSuspendCatching {
            if (request.bitkitClaim?.sharesBitcoin == true) {
                watchOnlyAccountRepo.prepareUnsignedClaim(authUrl, defaultWatchOnlyAccountName(request))
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

        val approvalResult = runSuspendCatching {
            request.bitkitClaim?.let { claimType ->
                val payload = claimType.unsignedPayload(preparedClaim, endpoint)
                pubkyRepo.approveAuthWithCompanionClaim(authUrl, request.clientId, payload).getOrThrow()
            } ?: pubkyRepo.approveAuth(authUrl, request.capabilities, request.clientId).getOrThrow()
        }
        if (approvalResult.isFailure) {
            val approvalError = checkNotNull(approvalResult.exceptionOrNull()) { "Authorization failed" }
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

        return runSuspendCatching {
            preparedClaim?.let { watchOnlyAccountRepo.markActive(it.account.id) }
        }.onFailure { handleApprovalFailure(it, authUrl) }.isSuccess
    }

    private suspend fun validatedUsdtEndpoint(request: PubkyAuthRequest): Endpoint? {
        if (request.bitkitClaim?.sharesUsdt != true || !_uiState.value.shareUsdt) return null
        return usdtRepo.paymentEndpoint().getOrThrow().also {
            if (it.value != _uiState.value.usdtAddress) throw PubkyAuthRequestError.InvalidPaymentDetails
        }
    }

    private fun transitionToAuthorizing(authUrl: String): PubkyAuthApprovalUiState? {
        val initialState = _uiState.value
        if (
            initialState.authUrl != authUrl ||
            (initialState.state != ApprovalState.Authorize && initialState.state != ApprovalState.Authenticating)
        ) {
            return null
        }
        val authorizingState = initialState.copy(state = ApprovalState.Authorizing)
        return authorizingState.takeIf { _uiState.compareAndSet(initialState, it) }
    }

    private fun resetForLoad(authUrl: String): Boolean {
        while (true) {
            val currentState = _uiState.value
            val isAuthorizing = currentState.state == ApprovalState.Authorizing &&
                inFlightAuthorization.get()?.authUrl == authUrl
            if (
                currentState.authUrl == authUrl &&
                (currentState.state == ApprovalState.Authenticating || isAuthorizing)
            ) {
                return false
            }
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
        if (error !is PubkyAlreadySignedInError) Logger.error("Auth approval failed", error, context = TAG)
        if (_uiState.value.authUrl != authUrl) return
        if (error is PubkyAlreadySignedInError) {
            ToastEventBus.send(
                type = Toast.ToastType.INFO,
                title = context.getString(R.string.pubky_auth__already_signed_in),
            )
            _effects.emit(PubkyAuthApprovalEffect.Dismiss)
            return
        }
        _uiState.update { it.copy(state = ApprovalState.Authorize) }
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.profile__auth_error_title),
            description = error.localizedPubkyAuthMessage(context),
        )
    }

    private fun defaultWatchOnlyAccountName(request: PubkyAuthRequest): String {
        val serviceName = request.serviceNames.firstOrNull()
            ?: context.getString(R.string.profile__auth_approval_service_unknown)
        return context.getString(R.string.profile__auth_approval_watch_only_account_default_name, serviceName)
    }

    private fun approvalProfile() = pubkyRepo.profile.value ?: pubkyRepo.publicKey.value?.let { publicKey ->
        PubkyProfile.forDisplay(
            publicKey = publicKey,
            name = pubkyRepo.displayName.value,
            imageUrl = pubkyRepo.displayImageUri.value,
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
    val clientId: String = "",
    val homeserverPublicKey: String? = null,
    val createsIdentity: Boolean = false,
    val serviceName: String = "",
    val permissions: ImmutableList<PubkyAuthPermission> = persistentListOf(),
    val bitkitClaim: PubkyAuthClaim? = null,
    val profile: PubkyProfile? = null,
    val usdtAddress: String? = null,
    val usdtUnavailable: Boolean = false,
    val shareUsdt: Boolean = true,
) {
    val canAuthorize: Boolean get() = bitkitClaim?.sharesUsdt != true || !shareUsdt || usdtAddress != null
}

sealed interface ApprovalState {
    data object Loading : ApprovalState
    data object WatchOnlyConsent : ApprovalState
    data object Authorize : ApprovalState
    data object Authenticating : ApprovalState
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
