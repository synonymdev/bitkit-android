package to.bitkit.ui.screens.profile

import android.content.Context
import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyContactError
import to.bitkit.repositories.PubkyProfileOwner
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.components.ProfileEditLink
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.usecases.ProfileRemovalConfirmation
import to.bitkit.usecases.ProfileRemovalCoordinator
import to.bitkit.usecases.ProfileRemovalFailure
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class EditProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val removalCoordinator: ProfileRemovalCoordinator,
) : ViewModel() {
    companion object {
        private const val TAG = "EditProfileViewModel"
    }

    private val screenOwner = pubkyRepo.profileOwner()
    private var preparationRevision = 0L
    private var removalConfirmation: ProfileRemovalConfirmation? = null
    private var removalFailure: ProfileRemovalFailure? = null

    private val _uiState = MutableStateFlow(EditProfileUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<EditProfileEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        loadProfile()
    }

    private fun loadProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val profile = pubkyRepo.profile.value
            val publicKey = pubkyRepo.publicKey.value.orEmpty()
            val removal = if (pubkyRepo.isRingAdoptedIdentity()) ProfileRemoval.UNLINK else ProfileRemoval.DELETE
            if (!removalCoordinator.isCurrent(screenOwner)) {
                _uiState.update { it.copy(isLoading = false) }
                return@launch
            }
            if (profile != null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        publicKey = publicKey,
                        removal = removal,
                        name = profile.name,
                        bio = profile.bio,
                        links = profile.links.map { link ->
                            ProfileEditLink(label = link.label, url = link.url)
                        }.toImmutableList(),
                        tags = profile.tags.toImmutableList(),
                        imageUrl = profile.imageUrl,
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false, publicKey = publicKey, removal = removal) }
            }
        }
    }

    fun onAvatarSelected(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.onSuccess { bytes ->
                if (bytes != null) {
                    _uiState.update { it.copy(newAvatarUri = uri, newAvatarBytes = bytes) }
                }
            }.onFailure {
                Logger.error("Failed to read avatar image", it, context = TAG)
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__avatar_read_error),
                )
            }
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name) }
    }

    fun onBioChange(bio: String) {
        _uiState.update { it.copy(bio = bio) }
    }

    fun addLink(label: String, url: String) {
        _uiState.update {
            it.copy(
                links = (it.links + ProfileEditLink(label = label, url = url)).toImmutableList(),
                showAddLinkSheet = false,
            )
        }
    }

    fun updateLinkUrl(index: Int, url: String) {
        _uiState.update {
            it.copy(
                links = it.links.mapIndexed { i, link ->
                    if (i == index) link.copy(url = url) else link
                }.toImmutableList(),
            )
        }
    }

    fun removeLink(index: Int) {
        _uiState.update {
            it.copy(links = it.links.filterIndexed { i, _ -> i != index }.toImmutableList())
        }
    }

    fun addTag(tag: String) {
        _uiState.update {
            it.copy(
                tags = (it.tags + tag).toImmutableList(),
                showAddTagSheet = false,
            )
        }
    }

    fun removeTag(index: Int) {
        _uiState.update {
            it.copy(tags = it.tags.filterIndexed { i, _ -> i != index }.toImmutableList())
        }
    }

    fun showAddLinkSheet() {
        _uiState.update { it.copy(showAddLinkSheet = true) }
    }

    fun dismissAddLinkSheet() {
        _uiState.update { it.copy(showAddLinkSheet = false) }
    }

    fun showAddTagSheet() {
        _uiState.update { it.copy(showAddTagSheet = true) }
    }

    fun dismissAddTagSheet() {
        _uiState.update { it.copy(showAddTagSheet = false) }
    }

    fun showDeleteConfirmation() {
        if (_uiState.value.isBusy || _uiState.value.isLoading) return
        viewModelScope.launch {
            prepareConfirmation(screenOwner) { removalCoordinator.prepareDeletion(screenOwner) }
        }
    }

    fun dismissDeleteDialog() {
        preparationRevision++
        removalConfirmation = null
        _uiState.update {
            it.copy(
                showDeleteDialog = false,
                showDeleteFailureDialog = removalFailure != null,
                canReviewRemoval = removalFailure?.canReview == true,
            )
        }
    }

    private suspend fun prepareConfirmation(
        owner: PubkyProfileOwner,
        prepare: suspend () -> Result<ProfileRemovalConfirmation>,
    ) {
        if (_uiState.value.isBusy) return
        val revision = ++preparationRevision
        _uiState.update { it.copy(isPreparingRemoval = true) }
        try {
            prepare().onSuccess { confirmation ->
                if (revision != preparationRevision || !removalCoordinator.isCurrent(confirmation.owner)) {
                    return@onSuccess
                }
                removalConfirmation = confirmation
                _uiState.update {
                    it.copy(
                        showDeleteDialog = true,
                        showDeleteFailureDialog = false,
                        isLocalDisconnect = confirmation.localOnly
                    )
                }
            }.onFailure { error ->
                if (revision != preparationRevision || !removalCoordinator.isCurrent(owner)) return@onFailure
                showRemovalError(error)
            }
        } finally {
            _uiState.update { it.copy(isPreparingRemoval = false) }
        }
    }

    fun save() {
        viewModelScope.launch {
            if (_uiState.value.isBusy) return@launch
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value

            val imageUrl = if (state.newAvatarBytes != null) {
                pubkyRepo.uploadAvatar(state.newAvatarBytes).getOrElse {
                    Logger.error("Failed to upload avatar", it, context = TAG)
                    _uiState.update { it.copy(isSaving = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__edit_save_error),
                        description = it.message,
                    )
                    return@launch
                }
            } else {
                state.imageUrl
            }

            pubkyRepo.saveProfile(
                name = state.name,
                bio = state.bio,
                links = state.links.map { PubkyProfileLink(label = it.label, url = it.url) },
                tags = state.tags,
                imageUrl = imageUrl,
            ).onSuccess {
                _uiState.update { it.copy(isSaving = false) }
                ToastEventBus.send(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.profile__edit_save_success),
                    testTag = "ProfileUpdatedToast",
                )
                _effects.emit(EditProfileEffect.SaveSuccess)
            }.onFailure {
                Logger.error("Failed to save profile", it, context = TAG)
                _uiState.update { it.copy(isSaving = false) }
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__edit_save_error),
                    description = it.message,
                )
            }
        }
    }

    fun deleteProfile() {
        if (_uiState.value.isBusy) return
        val confirmation = removalConfirmation ?: return
        removalConfirmation = null
        viewModelScope.launch { performRemoval(confirmation) }
    }

    fun retryDeleteProfile() {
        if (_uiState.value.isBusy) return
        val failure = removalFailure ?: return
        viewModelScope.launch {
            prepareConfirmation(
                failure.confirmation.owner
            ) { removalCoordinator.reviewAgain(failure) }
        }
    }

    fun dismissDeleteFailureDialog() {
        preparationRevision++
        removalConfirmation = null
        _uiState.update { it.copy(showDeleteFailureDialog = false) }
    }

    fun disconnectProfile() {
        if (_uiState.value.isBusy) return
        val failure = removalFailure ?: return
        viewModelScope.launch {
            prepareConfirmation(failure.recoveryOwner) { removalCoordinator.prepareLocalDisconnect(failure) }
        }
    }

    private suspend fun performRemoval(confirmation: ProfileRemovalConfirmation) {
        if (_uiState.value.isBusy) return
        _uiState.update { it.copy(showDeleteDialog = false, showDeleteFailureDialog = false, isDeleting = true) }
        try {
            removalCoordinator.remove(confirmation).onSuccess { endedOwner ->
                removalFailure = null
                if (removalCoordinator.isCurrent(endedOwner)) {
                    val successToast = if (confirmation.localOnly) {
                        ProfileRemoval.UNLINK.successToast
                    } else {
                        _uiState.value.removal.successToast
                    }
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(successToast),
                    )
                    if (removalCoordinator.isCurrent(endedOwner)) {
                        _effects.emit(EditProfileEffect.RemovalCompleted(endedOwner))
                    }
                }
            }.onFailure { error ->
                removalFailure = (error as? ProfileRemovalFailure) ?: removalFailure
                if (removalFailure != null) {
                    _uiState.update {
                        it.copy(
                            showDeleteFailureDialog = true,
                            canReviewRemoval = requireNotNull(removalFailure).canReview,
                        )
                    }
                } else {
                    if (removalCoordinator.isCurrent(confirmation.owner)) showRemovalError(error)
                }
            }
        } finally {
            removalFailure = removalCoordinator.failureFor(confirmation) ?: removalFailure
            _uiState.update {
                it.copy(
                    isDeleting = false,
                    showDeleteFailureDialog = removalFailure != null,
                    canReviewRemoval = removalFailure?.canReview == true,
                )
            }
        }
    }

    private suspend fun showRemovalError(error: Throwable) {
        if (error == PubkyContactError.ActiveSubscription) {
            ToastEventBus.send(
                Toast.ToastType.ERROR,
                context.getString(R.string.profile__delete_error_title),
                context.getString(R.string.profile__delete_active_subscription),
            )
        } else {
            ToastEventBus.send(
                Toast.ToastType.ERROR,
                context.getString(R.string.profile__disconnect_error),
                error.message,
            )
        }
    }

    fun isRemovalCurrent(effect: EditProfileEffect.RemovalCompleted): Boolean =
        removalCoordinator.isCurrent(effect.owner)
}

@Stable
data class EditProfileUiState(
    val publicKey: String = "",
    val removal: ProfileRemoval = ProfileRemoval.DELETE,
    val name: String = "",
    val bio: String = "",
    val links: ImmutableList<ProfileEditLink> = persistentListOf(),
    val tags: ImmutableList<String> = persistentListOf(),
    val imageUrl: String? = null,
    val newAvatarUri: Uri? = null,
    val newAvatarBytes: ByteArray? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isDeleting: Boolean = false,
    val isPreparingRemoval: Boolean = false,
    val isLocalDisconnect: Boolean = false,
    val canReviewRemoval: Boolean = true,
    val showDeleteDialog: Boolean = false,
    val showDeleteFailureDialog: Boolean = false,
    val showAddLinkSheet: Boolean = false,
    val showAddTagSheet: Boolean = false,
) {
    val isBusy: Boolean get() = isSaving || isDeleting || isPreparingRemoval
}

enum class ProfileRemoval(
    @StringRes val sectionLabel: Int,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    @StringRes val confirmTitle: Int,
    @StringRes val confirmDescription: Int,
    @StringRes val confirmText: Int,
    @StringRes val errorTitle: Int,
    @StringRes val errorDescription: Int,
    @StringRes val successToast: Int,
) {
    DELETE(
        sectionLabel = R.string.profile__edit_delete_section,
        label = R.string.profile__delete_profile,
        icon = R.drawable.ic_trash,
        confirmTitle = R.string.profile__delete_confirm_title,
        confirmDescription = R.string.profile__delete_confirm_description,
        confirmText = R.string.common__delete_yes,
        errorTitle = R.string.profile__delete_error_title,
        errorDescription = R.string.profile__delete_error_description,
        successToast = R.string.profile__delete_success,
    ),
    UNLINK(
        sectionLabel = R.string.profile__edit_unlink_section,
        label = R.string.profile__unlink_pubky,
        icon = R.drawable.ic_link,
        confirmTitle = R.string.profile__unlink_confirm_title,
        confirmDescription = R.string.profile__unlink_confirm_description,
        confirmText = R.string.profile__unlink_confirm,
        errorTitle = R.string.profile__unlink_error_title,
        errorDescription = R.string.profile__unlink_error_description,
        successToast = R.string.profile__unlink_success,
    ),
}

sealed interface EditProfileEffect {
    data object SaveSuccess : EditProfileEffect
    data class RemovalCompleted(val owner: PubkyProfileOwner) : EditProfileEffect
}
