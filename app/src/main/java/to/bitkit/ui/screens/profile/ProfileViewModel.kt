package to.bitkit.ui.screens.profile

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
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
import to.bitkit.R
import to.bitkit.data.PubkyCachedProfile
import to.bitkit.ext.setClipboardText
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyProfileOwner
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.usecases.ProfileRemovalConfirmation
import to.bitkit.usecases.ProfileRemovalCoordinator
import to.bitkit.usecases.ProfileRemovalFailure
import to.bitkit.utils.Logger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val removalCoordinator: ProfileRemovalCoordinator,
) : ViewModel() {
    companion object {
        private const val TAG = "ProfileViewModel"
        private val COPIED_POPUP_DURATION = 3.seconds
    }

    private var displayedOwner = pubkyRepo.profileOwner()
    private var ownerBoundToSession = displayedOwner.signIn != null
    private var removalConfirmation: ProfileRemovalConfirmation? = null
    private var removalFailure: ProfileRemovalFailure? = null
    private var preparationRevision = 0L
    private val _localDisconnect = MutableStateFlow(false)
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
    private val dialogControls = combine(
        _showSignOutDialog,
        _isSigningOut,
        _showAddTagSheet,
        _copiedPublicKey,
    ) { showSignOutDialog, isSigningOut, showAddTagSheet, copiedPublicKey ->
        ProfileControls(
            showSignOutDialog = showSignOutDialog,
            isSigningOut = isSigningOut,
            showAddTagSheet = showAddTagSheet,
            copiedPublicKey = copiedPublicKey
        )
    }

    private val controls = combine(
        dialogControls,
        pubkyRepo.profileRecoveryPending,
        _localDisconnect,
        pubkyRepo.profileRecoveryCanDisconnect,
    ) { controls, pending, localOnly, canDisconnect ->
        controls.copy(recoveryPending = pending, localDisconnect = localOnly, recoveryCanDisconnect = canDisconnect)
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
            controls = ProfileControls(
                recoveryPending = pubkyRepo.profileRecoveryPending.value,
                recoveryCanDisconnect = pubkyRepo.profileRecoveryCanDisconnect.value,
            ),
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
                if (pubkyRepo.profileRecoveryPending.first { it != null } == false) load()
            } finally {
                _isRefreshing.update { false }
            }
        }
    }

    fun showSignOutConfirmation() {
        if (_isSigningOut.value) return
        val state = uiState.value
        if (state.recoveryPending && !state.recoveryCanDisconnect) return
        val owner = if (state.recoveryPending) {
            pubkyRepo.profileOwner().takeIf { it.signIn == null } ?: state.owner
        } else {
            state.owner
        } ?: return
        val revision = ++preparationRevision
        _isSigningOut.value = true
        viewModelScope.launch {
            try {
                val failure = removalFailure
                val result = if (failure != null) {
                    removalCoordinator.prepareLocalDisconnect(failure)
                } else {
                    removalCoordinator.prepareSignOut(owner)
                }
                result.onSuccess { confirmation ->
                    if (revision != preparationRevision || !removalCoordinator.isCurrent(confirmation.owner)) {
                        return@onSuccess
                    }
                    removalConfirmation = confirmation
                    _localDisconnect.value = confirmation.localOnly
                    _showSignOutDialog.value = true
                }.onFailure { showRemovalError(it) }
            } finally {
                _isSigningOut.value = false
            }
        }
    }

    fun dismissSignOutDialog() {
        preparationRevision++
        removalConfirmation = null
        _showSignOutDialog.value = false
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
        if (_isSigningOut.value) return
        val confirmation = removalConfirmation ?: return
        removalConfirmation = null
        _isSigningOut.value = true
        _showSignOutDialog.value = false
        viewModelScope.launch {
            try {
                removalCoordinator.remove(confirmation).onSuccess { endedOwner ->
                    removalFailure = null
                    if (removalCoordinator.isCurrent(endedOwner)) _effects.emit(ProfileEffect.SignedOut(endedOwner))
                }.onFailure {
                    removalFailure = (it as? ProfileRemovalFailure) ?: removalFailure
                    showRemovalError(it)
                }
            } finally {
                removalFailure = removalCoordinator.failureFor(confirmation) ?: removalFailure
                _isSigningOut.value = false
            }
        }
    }

    fun isRemovalCurrent(effect: ProfileEffect.SignedOut): Boolean = removalCoordinator.isCurrent(effect.owner)

    private suspend fun showRemovalError(error: Throwable) {
        ToastEventBus.send(
            type = Toast.ToastType.ERROR,
            title = context.getString(R.string.profile__sign_out_title),
            description = error.message,
        )
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
    ): ProfileUiState {
        if (!ownerBoundToSession) {
            val restoredOwner = pubkyRepo.profileOwner()
            if (restoredOwner.storedRevision == displayedOwner.storedRevision && restoredOwner.signIn != null) {
                displayedOwner = restoredOwner
                ownerBoundToSession = true
            }
        }
        return ProfileUiState(
            profile = profile?.takeIf { controls.recoveryPending == false },
            cachedProfile = cachedProfile?.takeIf {
                controls.recoveryPending == false && isLoading && it.publicKey == publicKey
            },
            publicKey = publicKey ?: profile?.publicKey,
            canEdit = controls.recoveryPending == false && publicKey != null &&
                publicKey == pubkyRepo.profile.value?.publicKey,
            isLoading = controls.recoveryPending == null || (controls.recoveryPending == false && isLoading),
            recoveryPending = controls.recoveryPending == true,
            recoveryCanDisconnect = controls.recoveryCanDisconnect,
            localDisconnect = controls.localDisconnect,
            owner = displayedOwner,
            showSignOutDialog = controls.showSignOutDialog,
            isSigningOut = controls.isSigningOut,
            showAddTagSheet = controls.showAddTagSheet,
            copiedPublicKey = controls.copiedPublicKey,
        )
    }

    private fun updateTags(
        transform: (List<String>) -> List<String>,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            tagUpdateMutex.withLock {
                if (pubkyRepo.profileRecoveryPending.value != false) return@withLock
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
    val recoveryPending: Boolean = false,
    val recoveryCanDisconnect: Boolean = false,
    val localDisconnect: Boolean = false,
    val owner: PubkyProfileOwner? = null,
    val isLoading: Boolean = true,
    val showSignOutDialog: Boolean = false,
    val isSigningOut: Boolean = false,
    val showAddTagSheet: Boolean = false,
    val copiedPublicKey: String? = null,
)

private data class ProfileControls(
    val recoveryPending: Boolean? = null,
    val recoveryCanDisconnect: Boolean = false,
    val localDisconnect: Boolean = false,
    val showSignOutDialog: Boolean = false,
    val isSigningOut: Boolean = false,
    val showAddTagSheet: Boolean = false,
    val copiedPublicKey: String? = null,
)

sealed interface ProfileEffect {
    data class SignedOut(val owner: PubkyProfileOwner) : ProfileEffect
}
