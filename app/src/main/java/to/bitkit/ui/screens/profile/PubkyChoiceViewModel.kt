package to.bitkit.ui.screens.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.VisibleForTesting
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyRingAuthUrlBuilder
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
        private const val IS_RING_AUTH_ENABLED = false
        internal const val PUBKY_RING_PACKAGE = "to.pubky.ring"
    }

    private val _uiState = MutableStateFlow(PubkyChoiceUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkyChoiceEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var approvalJob: Job? = null

    init {
        viewModelScope.launch {
            pubkyRepo.authCancelEvents.collect {
                approvalJob?.cancel()
                approvalJob = null
                _uiState.update { it.copy(isWaitingForRing = false, isLoadingAfterAuth = false) }
            }
        }
        viewModelScope.launch {
            pubkyRepo.isAuthenticated.collectLatest {
                if (it && approvalJob?.isActive != true && !_uiState.value.isLoadingAfterAuth) {
                    _uiState.update { state -> state.copy(navigateToProfile = true) }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.isWaitingForRing) {
            pubkyRepo.cancelAuthenticationSync()
        }
    }

    fun startRingAuth() {
        viewModelScope.launch {
            if (!IS_RING_AUTH_ENABLED) {
                ToastEventBus.send(
                    type = Toast.ToastType.INFO,
                    title = context.getString(R.string.coming_soon__title),
                    description = context.getString(R.string.coming_soon__description),
                )
                return@launch
            }

            if (_uiState.value.isWaitingForRing) {
                approvalJob?.cancel()
                approvalJob = null
                _uiState.update { it.copy(isWaitingForRing = false) }
                pubkyRepo.cancelAuthentication()
            }

            if (!isRingInstalled()) {
                showRingNotInstalledDialog()
                return@launch
            }

            pubkyRepo.startAuthentication()
                .onSuccess { authRequest ->
                    val callbackAuthUrl = PubkyRingAuthUrlBuilder.addCallbacks(
                        authUrl = authRequest.authUrl,
                        nonce = authRequest.callbackNonce,
                    ) ?: authRequest.authUrl
                    val ringIntent = createRingAuthIntent(callbackAuthUrl)
                    if (!canOpenWithRing(ringIntent)) {
                        cancelAuthAndShowRingDialog()
                        return@launch
                    }

                    _uiState.update { it.copy(isWaitingForRing = true) }
                    _effects.emit(PubkyChoiceEffect.OpenRingAuth(ringIntent))
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

    fun onRingLaunchFailed() {
        viewModelScope.launch {
            cancelAuthAndShowRingDialog()
        }
    }

    @VisibleForTesting
    internal fun waitForApproval() {
        if (approvalJob?.isActive == true) return

        approvalJob = viewModelScope.launch {
            pubkyRepo.completeAuthentication()
                .onSuccess {
                    _uiState.update { it.copy(isWaitingForRing = false, isLoadingAfterAuth = true) }
                    pubkyRepo.prepareImport()
                        .onSuccess {
                            _uiState.update { state -> state.copy(isLoadingAfterAuth = false) }
                            val hasContacts = pubkyRepo.pendingImportContacts.value.isNotEmpty()
                            if (hasContacts) {
                                _effects.emit(PubkyChoiceEffect.NavigateToContactImportOverview)
                            } else {
                                _effects.emit(PubkyChoiceEffect.NavigateToPayContacts)
                            }
                        }
                        .onFailure {
                            Logger.error("Preparing contact import failed", it, context = TAG)
                            _uiState.update { state -> state.copy(isLoadingAfterAuth = false) }
                            ToastEventBus.send(
                                type = Toast.ToastType.ERROR,
                                title = context.getString(R.string.common__error),
                                description = it.message,
                            )
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

    fun clearProfileNavigation() {
        _uiState.update { it.copy(navigateToProfile = false) }
    }

    @VisibleForTesting
    internal fun createRingAuthIntent(authUrl: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
        setPackage(PUBKY_RING_PACKAGE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    @VisibleForTesting
    internal fun isRingInstalled(): Boolean =
        context.packageManager.getLaunchIntentForPackage(PUBKY_RING_PACKAGE) != null

    @VisibleForTesting
    internal fun canOpenWithRing(intent: Intent): Boolean =
        intent.resolveActivity(context.packageManager) != null

    private suspend fun cancelAuthAndShowRingDialog() {
        approvalJob?.cancel()
        approvalJob = null
        pubkyRepo.cancelAuthentication()
        showRingNotInstalledDialog()
    }

    private fun showRingNotInstalledDialog() {
        _uiState.update {
            it.copy(
                isWaitingForRing = false,
                isLoadingAfterAuth = false,
                showRingNotInstalledDialog = true,
            )
        }
    }
}

@Immutable
data class PubkyChoiceUiState(
    val isWaitingForRing: Boolean = false,
    val isLoadingAfterAuth: Boolean = false,
    val showRingNotInstalledDialog: Boolean = false,
    val navigateToProfile: Boolean = false,
)

sealed interface PubkyChoiceEffect {
    data class OpenRingAuth(val intent: Intent) : PubkyChoiceEffect
    data object NavigateToCreateProfile : PubkyChoiceEffect
    data object NavigateToContactImportOverview : PubkyChoiceEffect
    data object NavigateToPayContacts : PubkyChoiceEffect
}
