package to.bitkit.ui.screens.profile

import android.content.Context
import android.net.Uri
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
import to.bitkit.repositories.PrivatePaykitRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.components.ProfileEditLink
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class EditProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val privatePaykitRepo: PrivatePaykitRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "EditProfileViewModel"
    }

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
            if (profile != null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        publicKey = publicKey,
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
                _uiState.update { it.copy(isLoading = false, publicKey = publicKey) }
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
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun save() {
        viewModelScope.launch {
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
        viewModelScope.launch {
            attemptDeleteProfile()
        }
    }

    fun retryDeleteProfile() {
        viewModelScope.launch {
            attemptDeleteProfile()
        }
    }

    fun dismissDeleteFailureDialog() {
        _uiState.update { it.copy(showDeleteFailureDialog = false) }
    }

    fun disconnectProfile() {
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteFailureDialog = false, isSaving = true) }
            privatePaykitRepo.removePublishedEndpointsForCleanup(TAG)
            val result = pubkyRepo.signOut()
            if (result.isSuccess) {
                privatePaykitRepo.closeAndClear()
                _uiState.update { it.copy(isSaving = false) }
                _effects.emit(EditProfileEffect.DisconnectSuccess)
            } else {
                val error = requireNotNull(result.exceptionOrNull()) { "Disconnect failed without an error" }
                Logger.error("Failed to disconnect profile", error, context = TAG)
                _uiState.update { it.copy(isSaving = false) }
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__disconnect_error),
                    description = error.message,
                )
            }
        }
    }

    private suspend fun attemptDeleteProfile() {
        _uiState.update {
            it.copy(
                showDeleteDialog = false,
                showDeleteFailureDialog = false,
                isSaving = true,
            )
        }
        privatePaykitRepo.removePublishedEndpointsForCleanup(TAG)
        val result = pubkyRepo.deleteProfileWithSessionRetry()
        if (result.isSuccess) {
            privatePaykitRepo.closeAndClear()
            _uiState.update { it.copy(isSaving = false) }
            ToastEventBus.send(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.profile__delete_success),
            )
            _effects.emit(EditProfileEffect.DeleteSuccess)
        } else {
            val error = requireNotNull(result.exceptionOrNull()) { "Profile delete failed without an error" }
            Logger.error("Failed to delete profile", error, context = TAG)
            _uiState.update {
                it.copy(
                    isSaving = false,
                    showDeleteFailureDialog = true,
                )
            }
        }
    }
}

@Stable
data class EditProfileUiState(
    val publicKey: String = "",
    val name: String = "",
    val bio: String = "",
    val links: ImmutableList<ProfileEditLink> = persistentListOf(),
    val tags: ImmutableList<String> = persistentListOf(),
    val imageUrl: String? = null,
    val newAvatarUri: Uri? = null,
    val newAvatarBytes: ByteArray? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showDeleteFailureDialog: Boolean = false,
    val showAddLinkSheet: Boolean = false,
    val showAddTagSheet: Boolean = false,
)

sealed interface EditProfileEffect {
    data object SaveSuccess : EditProfileEffect
    data object DeleteSuccess : EditProfileEffect
    data object DisconnectSuccess : EditProfileEffect
}
