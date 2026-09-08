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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val _showAddTagSheet = MutableStateFlow(false)
    private val tagUpdateMutex = Mutex()
    private val controls = combine(
        _showSignOutDialog,
        _isSigningOut,
        _showAddTagSheet,
    ) { showSignOutDialog, isSigningOut, showAddTagSheet ->
        ProfileControls(showSignOutDialog, isSigningOut, showAddTagSheet)
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        pubkyRepo.profile,
        pubkyRepo.publicKey,
        pubkyRepo.isLoadingProfile,
        controls,
    ) { profile, publicKey, isLoading, controls ->
        ProfileUiState(
            profile = profile,
            publicKey = publicKey,
            isLoading = isLoading,
            showSignOutDialog = controls.showSignOutDialog,
            isSigningOut = controls.isSigningOut,
            showAddTagSheet = controls.showAddTagSheet,
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

    fun showAddTagSheet() {
        _showAddTagSheet.update { true }
    }

    fun dismissAddTagSheet() {
        _showAddTagSheet.update { false }
    }

    fun addTag(tag: String) {
        updateTags(
            transform = { (it + tag).distinct() },
            onSuccess = { _showAddTagSheet.update { false } },
        )
    }

    fun removeTag(tag: String) {
        updateTags(transform = { tags -> tags.filterNot { it == tag } })
    }

    fun signOut() {
        viewModelScope.launch {
            _isSigningOut.update { true }
            _showSignOutDialog.update { false }
            val cleanupResult = privatePaykitRepo.removePublishedEndpointsForCleanup(TAG)
            if (cleanupResult.isFailure) {
                val error = requireNotNull(cleanupResult.exceptionOrNull()) {
                    "Private Paykit cleanup failed without an error"
                }
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__sign_out_title),
                    description = error.message,
                )
                _isSigningOut.update { false }
                return@launch
            }

            val result = pubkyRepo.signOut()
            if (result.isSuccess) {
                privatePaykitRepo.closeAndClear()
                _effects.emit(ProfileEffect.SignedOut)
            } else {
                val error = requireNotNull(result.exceptionOrNull()) { "Sign out failed without an error" }
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__sign_out_title),
                    description = error.message,
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

    private fun updateTags(
        transform: (List<String>) -> List<String>,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            tagUpdateMutex.withLock {
                val profile = pubkyRepo.profile.value ?: return@withLock
                val tags = transform(profile.tags)
                if (tags == profile.tags) {
                    onSuccess()
                    return@withLock
                }

                pubkyRepo.saveProfile(
                    name = profile.name,
                    bio = profile.bio,
                    links = profile.links,
                    tags = tags,
                    imageUrl = profile.imageUrl,
                ).onSuccess {
                    onSuccess()
                }.onFailure {
                    Logger.error("Failed to update profile tags", it, context = TAG)
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__edit_save_error),
                        description = it.message,
                    )
                }
            }
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
    val showAddTagSheet: Boolean = false,
)

private data class ProfileControls(
    val showSignOutDialog: Boolean,
    val isSigningOut: Boolean,
    val showAddTagSheet: Boolean,
)

sealed interface ProfileEffect {
    data object SignedOut : ProfileEffect
}
