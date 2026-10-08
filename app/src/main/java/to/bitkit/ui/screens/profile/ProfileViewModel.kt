package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import to.bitkit.R
import to.bitkit.data.PubkyCachedProfile
import to.bitkit.ext.runSuspendCatching
import to.bitkit.ext.setClipboardText
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
        private val COPIED_POPUP_DURATION = 3.seconds
    }

    private val _showSignOutDialog = MutableStateFlow(false)
    private val _isSigningOut = MutableStateFlow(false)
    private val _showAddTagSheet = MutableStateFlow(false)
    private val _copiedPublicKey = MutableStateFlow<String?>(null)
    private val tagUpdateMutex = Mutex()
    private var hideCopiedPopupJob: Job? = null
    private var profileLoadJob: Job? = null
    private val _isRefreshing = MutableStateFlow(true)
    private val displayProfile = combine(pubkyRepo.profile, pubkyRepo.readOnlyProfile) { profile, readOnly ->
        profile ?: readOnly
    }
    private val isLoading = combine(
        pubkyRepo.isLoadingProfile,
        pubkyRepo.isRestoringSession,
        _isRefreshing,
    ) { loading, restoring, refreshing -> loading || restoring || refreshing }
    private val controls = combine(
        _showSignOutDialog,
        _isSigningOut,
        _showAddTagSheet,
        _copiedPublicKey,
    ) { showSignOutDialog, isSigningOut, showAddTagSheet, copiedPublicKey ->
        ProfileControls(showSignOutDialog, isSigningOut, showAddTagSheet, copiedPublicKey)
    }

    init {
        if (pubkyRepo.isLoadingProfile.value) loadProfileAfterInFlightLoad() else loadProfile()
    }

    val uiState: StateFlow<ProfileUiState> = combine(
        displayProfile,
        pubkyRepo.publicKey,
        isLoading,
        pubkyRepo.cachedProfile,
        controls,
    ) { profile, publicKey, isLoading, cachedProfile, controls ->
        profileUiState(profile, publicKey, isLoading, cachedProfile, controls)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        profileUiState(
            profile = pubkyRepo.profile.value ?: pubkyRepo.readOnlyProfile.value,
            publicKey = pubkyRepo.publicKey.value,
            isLoading = pubkyRepo.isLoadingProfile.value || pubkyRepo.isRestoringSession.value || _isRefreshing.value,
            cachedProfile = pubkyRepo.cachedProfile.value,
            controls = ProfileControls(),
        ),
    )

    private val _effects = MutableSharedFlow<ProfileEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    fun loadProfile() = launchProfileLoad {
        if (pubkyRepo.publicKey.value == null) {
            coroutineScope {
                launch { pubkyRepo.loadProfile() }
                pubkyRepo.restoreSessionIfNeeded()
            }
        } else {
            val restored = pubkyRepo.restoreSessionIfNeeded()
            if (!restored) pubkyRepo.loadProfile()
        }
    }

    private fun loadProfileAfterInFlightLoad() = launchProfileLoad {
        pubkyRepo.isLoadingProfile.first { !it }
        val publicKey = pubkyRepo.publicKey.value ?: return@launchProfileLoad
        if (pubkyRepo.profile.value?.publicKey != publicKey) pubkyRepo.loadProfile()
    }

    private fun launchProfileLoad(load: suspend () -> Unit) {
        if (profileLoadJob?.isActive == true) return
        profileLoadJob = viewModelScope.launch {
            _isRefreshing.update { true }
            try {
                load()
            } finally {
                _isRefreshing.update { false }
            }
        }
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
            if (_isSigningOut.value) return@launch
            _isSigningOut.update { true }
            _showSignOutDialog.update { false }
            try {
                val result = runSuspendCatching {
                    withContext(NonCancellable) {
                        if (!pubkyRepo.forgetUnrestoredIdentity().getOrThrow()) {
                            privatePaykitRepo.removePublishedEndpointsForCleanup(TAG).getOrThrow()
                            pubkyRepo.signOut().getOrThrow()
                        }
                        privatePaykitRepo.closeAndClear()
                    }
                }
                if (result.isSuccess) {
                    _effects.emit(ProfileEffect.SignedOut)
                } else {
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__sign_out_title),
                        description = result.exceptionOrNull()?.message,
                    )
                }
            } finally {
                _isSigningOut.update { false }
            }
        }
    }

    fun copyPublicKey() {
        val pk = pubkyRepo.publicKey.value ?: pubkyRepo.readOnlyProfile.value?.publicKey ?: return
        context.setClipboardText(pk, context.getString(R.string.profile__public_key))
        _copiedPublicKey.update { pk }
        hideCopiedPopupJob?.cancel()
        hideCopiedPopupJob = viewModelScope.launch {
            delay(COPIED_POPUP_DURATION)
            _copiedPublicKey.update { null }
        }
    }

    fun dismissCopiedPopup() {
        hideCopiedPopupJob?.cancel()
        _copiedPublicKey.update { null }
    }

    private fun profileUiState(
        profile: PubkyProfile?,
        publicKey: String?,
        isLoading: Boolean,
        cachedProfile: PubkyCachedProfile?,
        controls: ProfileControls,
    ) = ProfileUiState(
        profile = profile,
        cachedProfile = cachedProfile?.takeIf { isLoading && it.publicKey == publicKey },
        publicKey = publicKey ?: profile?.publicKey,
        canEdit = publicKey != null && publicKey == pubkyRepo.profile.value?.publicKey,
        isLoading = isLoading,
        showSignOutDialog = controls.showSignOutDialog,
        isSigningOut = controls.isSigningOut,
        showAddTagSheet = controls.showAddTagSheet,
        copiedPublicKey = controls.copiedPublicKey,
    )

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
    val cachedProfile: PubkyCachedProfile? = null,
    val publicKey: String? = null,
    val canEdit: Boolean = false,
    val isLoading: Boolean = true,
    val showSignOutDialog: Boolean = false,
    val isSigningOut: Boolean = false,
    val showAddTagSheet: Boolean = false,
    val copiedPublicKey: String? = null,
)

private data class ProfileControls(
    val showSignOutDialog: Boolean = false,
    val isSigningOut: Boolean = false,
    val showAddTagSheet: Boolean = false,
    val copiedPublicKey: String? = null,
)

sealed interface ProfileEffect {
    data object SignedOut : ProfileEffect
}
