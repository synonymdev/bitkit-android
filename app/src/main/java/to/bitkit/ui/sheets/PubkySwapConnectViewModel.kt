package to.bitkit.ui.sheets

import android.content.Context
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsStore
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkySwapProvider
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PubkySecretAccessError
import to.bitkit.repositories.PubkySwapError
import to.bitkit.repositories.PubkySwapRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class PubkySwapConnectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val pubkySwapRepo: PubkySwapRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {
    companion object {
        private const val TAG = "PubkySwapConnectVM"
    }

    private val _uiState = MutableStateFlow(PubkySwapConnectUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<PubkySwapConnectEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var loadJob: Job? = null

    fun load(provider: PubkySwapProvider) {
        if (_uiState.value.provider == provider) return
        _uiState.update { PubkySwapConnectUiState(provider = provider) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val current = settingsStore.pubkySwapProvider.first()
            val canSetUpProfile = PaykitFeatureFlags.isUiEnabled(settingsStore.isPaykitEnabled.first())
            _uiState.update {
                it.copy(
                    replacedProvider = current?.takeIf { saved -> saved != provider },
                    canSetUpProfile = canSetUpProfile,
                )
            }
            launch {
                pubkyRepo.publicKey.collect { identity -> _uiState.update { it.copy(identity = identity) } }
            }
            val profile = pubkyRepo.fetchDisplayProfile(provider.publicKey).getOrNull()
            _uiState.update { it.copy(providerProfile = profile) }
        }
    }

    fun connect() {
        val state = _uiState.value
        val provider = state.provider ?: return
        if (state.isConnecting) return
        _uiState.update { it.copy(isConnecting = true, errorText = null) }
        viewModelScope.launch {
            pubkySwapRepo.connect(provider)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.pubky_swap__success_title),
                        description = context.getString(R.string.pubky_swap__success_description),
                    )
                    _effects.emit(PubkySwapConnectEffect.Dismiss)
                }
                .onFailure { error ->
                    Logger.error("Failed to connect swap provider '${provider.publicKey}'", error, context = TAG)
                    _uiState.update { it.copy(isConnecting = false, errorText = errorText(error)) }
                }
        }
    }

    fun setUpProfile() {
        val provider = _uiState.value.provider ?: return
        pubkySwapRepo.setPendingConnection(provider)
        _effects.tryEmit(PubkySwapConnectEffect.SetUpProfile)
    }

    private fun errorText(error: Throwable): String = context.getString(
        when (error) {
            is PubkySecretAccessError.NotSignedIn -> R.string.pubky_swap__error_no_identity
            is PubkySecretAccessError -> R.string.pubky_swap__error_identity_unavailable
            is PubkySwapError.PendingSwaps -> R.string.pubky_swap__error_pending_swaps
            is PubkySwapError.Busy -> R.string.pubky_swap__error_busy
            is PubkySwapError.UnsupportedNetwork -> R.string.pubky_swap__network_mismatch_text
            is PubkySwapError.Timeout -> R.string.pubky_swap__error_timeout
            else -> R.string.pubky_swap__error_generic
        }
    )
}

@Immutable
data class PubkySwapConnectUiState(
    val provider: PubkySwapProvider? = null,
    val providerProfile: PubkyProfile? = null,
    val identity: String? = null,
    val replacedProvider: PubkySwapProvider? = null,
    val canSetUpProfile: Boolean = false,
    val isConnecting: Boolean = false,
    val errorText: String? = null,
)

sealed interface PubkySwapConnectEffect {
    data object Dismiss : PubkySwapConnectEffect
    data object SetUpProfile : PubkySwapConnectEffect
}
