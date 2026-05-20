package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
    }

    private val _showSignOutDialog = MutableStateFlow(false)
    private val _isSigningOut = MutableStateFlow(false)

    val uiState: StateFlow<ProfileUiState> = combine(
        pubkyRepo.profile,
        pubkyRepo.publicKey,
        pubkyRepo.isLoadingProfile,
        _showSignOutDialog,
        _isSigningOut,
    ) { profile, publicKey, isLoading, showSignOutDialog, isSigningOut ->
        ProfileUiState(
            profile = profile,
            publicKey = publicKey,
            isLoading = isLoading,
            showSignOutDialog = showSignOutDialog,
            isSigningOut = isSigningOut,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    private val _effects = MutableSharedFlow<ProfileEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch { pubkyRepo.loadProfile() }
    }

    fun showSignOutConfirmation() {
        _showSignOutDialog.update { true }
    }

    fun dismissSignOutDialog() {
        _showSignOutDialog.update { false }
    }

    fun signOut() {
        viewModelScope.launch {
            _isSigningOut.update { true }
            _showSignOutDialog.update { false }
            privatePaykitRepo.removePublishedEndpointsBestEffort(TAG)
            privatePaykitRepo.closeAndClear(markProfileRecoveryPending = true)
            pubkyRepo.signOut()
                .onSuccess {
                    _effects.emit(ProfileEffect.SignedOut)
                }
                .onFailure {
                    Logger.error("Sign out failed", it, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__sign_out_title),
                        description = it.message,
                    )
                }
            _isSigningOut.update { false }
        }
    }

    fun copyPublicKey() {
        val pk = pubkyRepo.publicKey.value ?: return
        context.setClipboardText(pk, context.getString(R.string.profile__public_key))
        viewModelScope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
                testTag = "ProfilePubkyCopiedToast",
            )
        }
    }
}

@Stable
data class ProfileUiState(
    val profile: PubkyProfile? = null,
    val publicKey: String? = null,
    val isLoading: Boolean = false,
    val showSignOutDialog: Boolean = false,
    val isSigningOut: Boolean = false,
)

sealed interface ProfileEffect {
    data object SignedOut : ProfileEffect
}
