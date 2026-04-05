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
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.components.ProfileEditLink
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class CreateProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {
    companion object {
        private const val TAG = "CreateProfileViewModel"
    }

    private val _uiState = MutableStateFlow(CreateProfileUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<CreateProfileEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        deriveAndCheckRemote()
    }

    private fun deriveAndCheckRemote() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            pubkyRepo.deriveKeys()
                .onSuccess { (publicKey, _) ->
                    _uiState.update { it.copy(derivedPublicKey = publicKey) }
                    checkForExistingProfile(publicKey)
                }
                .onFailure {
                    Logger.error("Failed to derive keys", it, context = TAG)
                    _uiState.update { it.copy(isLoading = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.profile__auth_error_title),
                        description = it.message,
                    )
                }
        }
    }

    private suspend fun checkForExistingProfile(publicKey: String) {
        pubkyRepo.fetchRemoteProfile(publicKey)
            .onSuccess { profile ->
                if (profile != null) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isRestoring = true,
                            name = profile.name,
                            bio = profile.bio,
                            links = profile.links.map { link ->
                                ProfileEditLink(label = link.label, url = link.url)
                            }.toImmutableList(),
                            tags = profile.tags.toImmutableList(),
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
            .onFailure {
                Logger.debug("No existing remote profile found for '$publicKey'", context = TAG)
                _uiState.update { it.copy(isLoading = false) }
            }
    }

    fun onAvatarSelected(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.onSuccess { bytes ->
                if (bytes != null) {
                    _uiState.update { it.copy(avatarUri = uri, avatarBytes = bytes) }
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

    fun save() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val state = _uiState.value
            pubkyRepo.createIdentity(
                name = state.name,
                bio = state.bio,
                links = state.links.map { PubkyProfileLink(label = it.label, url = it.url) },
                tags = state.tags,
                avatarBytes = state.avatarBytes,
            ).onSuccess {
                _uiState.update { it.copy(isSaving = false) }
                ToastEventBus.send(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.profile__create_success),
                )
                _effects.emit(CreateProfileEffect.CreateSuccess)
            }.onFailure {
                Logger.error("Failed to create identity", it, context = TAG)
                _uiState.update { it.copy(isSaving = false) }
                ToastEventBus.send(
                    type = Toast.ToastType.ERROR,
                    title = context.getString(R.string.profile__create_error),
                    description = it.message,
                )
            }
        }
    }
}

@Stable
data class CreateProfileUiState(
    val derivedPublicKey: String? = null,
    val name: String = "",
    val bio: String = "",
    val links: ImmutableList<ProfileEditLink> = persistentListOf(),
    val tags: ImmutableList<String> = persistentListOf(),
    val avatarUri: Uri? = null,
    val avatarBytes: ByteArray? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val isRestoring: Boolean = false,
    val showAddLinkSheet: Boolean = false,
    val showAddTagSheet: Boolean = false,
)

sealed interface CreateProfileEffect {
    data object CreateSuccess : CreateProfileEffect
}
