package to.bitkit.ui.screens.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {

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
            pubkyRepo.signOut()
                .onSuccess {
                    _effects.emit(ProfileEffect.SignedOut)
                }
                .onFailure { e ->
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__sign_out_title),
                        description = e.message,
                    )
                }
            _isSigningOut.update { false }
        }
    }

    fun copyPublicKey() {
        val pk = pubkyRepo.publicKey.value ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Public Key", pk))
        viewModelScope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
            )
        }
    }

    fun sharePublicKey() {
        val pk = pubkyRepo.publicKey.value ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, pk)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

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
