package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.components.ProfileEditLink
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@Suppress("TooManyFunctions")
@HiltViewModel
class EditContactViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "EditContactViewModel"
    }

    private val publicKey: String = checkNotNull(
        savedStateHandle["publicKey"],
    ) { "publicKey not found in SavedStateHandle" }

    private val _uiState = MutableStateFlow(EditContactUiState(publicKey = publicKey))
    val uiState: StateFlow<EditContactUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<EditContactEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        observeContactUpdates()
        retryLoadContact()
    }

    fun retryLoadContact() {
        viewModelScope.launch {
            val cachedContact = pubkyRepo.contacts.value.find { it.publicKey == publicKey }
            if (cachedContact != null) {
                applyContact(cachedContact)
                return@launch
            }

            _uiState.update {
                it.copy(
                    isLoading = true,
                    isMissing = false,
                )
            }

            pubkyRepo.loadContacts()

            val refreshedContact = pubkyRepo.contacts.value.find { it.publicKey == publicKey }
            if (refreshedContact != null) {
                applyContact(refreshedContact)
                return@launch
            }

            Logger.warn("Failed to find contact '$publicKey' after refresh", context = TAG)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isMissing = true,
                )
            }
        }
    }

    private fun observeContactUpdates() {
        viewModelScope.launch {
            pubkyRepo.contacts.collectLatest { contacts ->
                contacts.find { it.publicKey == publicKey }?.let { applyContact(it) }
            }
        }
    }

    private fun applyContact(contact: PubkyProfile) {
        _uiState.update {
            it.copy(
                name = contact.name,
                bio = contact.bio,
                imageUrl = contact.imageUrl,
                links = contact.links.map { link ->
                    ProfileEditLink(label = link.label, url = link.url)
                }.toImmutableList(),
                tags = contact.tags.toImmutableList(),
                isLoading = false,
                isMissing = false,
            )
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
                links = (it.links + ProfileEditLink(label, url)).toImmutableList(),
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
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            pubkyRepo.updateContact(
                publicKey = publicKey,
                name = state.name,
                bio = state.bio,
                imageUrl = state.imageUrl,
                links = state.links.map { PubkyProfileLink(it.label, it.url) },
                tags = state.tags,
            ).onSuccess {
                ToastEventBus.send(
                    type = Toast.ToastType.SUCCESS,
                    title = context.getString(R.string.contacts__edit_contact_saved),
                )
                _effects.emit(EditContactEffect.SaveSuccess)
            }.onFailure {
                Logger.error("Failed to save contact '$publicKey'", it, context = TAG)
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    fun deleteContact() {
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteDialog = false, isSaving = true) }
            pubkyRepo.removeContact(publicKey)
                .onSuccess {
                    _effects.emit(EditContactEffect.DeleteSuccess)
                }
                .onFailure {
                    Logger.error("Failed to delete contact '$publicKey'", it, context = TAG)
                    _uiState.update { it.copy(isSaving = false) }
                }
        }
    }
}

@Immutable
data class EditContactUiState(
    val publicKey: String = "",
    val name: String = "",
    val bio: String = "",
    val imageUrl: String? = null,
    val links: ImmutableList<ProfileEditLink> = persistentListOf(),
    val tags: ImmutableList<String> = persistentListOf(),
    val isLoading: Boolean = true,
    val isMissing: Boolean = false,
    val isSaving: Boolean = false,
    val showDeleteDialog: Boolean = false,
    val showAddLinkSheet: Boolean = false,
    val showAddTagSheet: Boolean = false,
)

sealed interface EditContactEffect {
    data object SaveSuccess : EditContactEffect
    data object DeleteSuccess : EditContactEffect
}
