package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.compose.runtime.Stable
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyProfileLink
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.PublicPaykitPaymentResult
import to.bitkit.repositories.PublicPaykitRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    private val publicPaykitRepo: PublicPaykitRepo,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "ContactDetailViewModel"
    }

    private val publicKey: String = checkNotNull(
        savedStateHandle["publicKey"],
    ) { "publicKey not found in SavedStateHandle" }

    private val _uiState = MutableStateFlow(ContactDetailUiState())
    val uiState: StateFlow<ContactDetailUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ContactDetailEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        loadContact()
        loadPaymentEndpoint()
        observeContactUpdates()
    }

    fun loadContact() {
        viewModelScope.launch {
            val cached = pubkyRepo.contacts.value.find { it.publicKey == publicKey }
            if (cached != null) {
                _uiState.update {
                    it.copy(
                        profile = cached,
                        tags = cached.tags.toImmutableList(),
                        isLoading = false,
                    )
                }
                return@launch
            }
            _uiState.update { it.copy(isLoading = true) }
            pubkyRepo.fetchContactProfile(publicKey)
                .onSuccess { profile ->
                    _uiState.update {
                        it.copy(
                            profile = profile,
                            tags = profile.tags.toImmutableList(),
                            isLoading = false,
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            profile = it.profile ?: PubkyProfile.placeholder(publicKey),
                            isLoading = false,
                        )
                    }
                }
        }
    }

    private fun loadPaymentEndpoint() {
        viewModelScope.launch {
            publicPaykitRepo.hasPayablePublicEndpoint(publicKey)
                .onSuccess { hasEndpoint ->
                    _uiState.update { it.copy(hasPublicPaymentEndpoint = hasEndpoint) }
                }
                .onFailure {
                    Logger.warn("Failed to load public Paykit endpoint for '$publicKey'", it, context = TAG)
                }
        }
    }

    fun payContact() {
        viewModelScope.launch {
            publicPaykitRepo.beginPayment(publicKey)
                .onSuccess { result ->
                    when (result) {
                        is PublicPaykitPaymentResult.Opened ->
                            _effects.emit(ContactDetailEffect.OpenPayment(result.paymentRequest, publicKey))
                        PublicPaykitPaymentResult.NoEndpoint ->
                            showPayError(R.string.slashtags__error_pay_empty_msg)
                        PublicPaykitPaymentResult.NotOpened ->
                            showPayError(R.string.slashtags__error_pay_not_opened_msg)
                    }
                }
                .onFailure {
                    Logger.warn("Failed to begin public Paykit payment for '$publicKey'", it, context = TAG)
                    showPayError(R.string.slashtags__error_pay_not_opened_msg)
                }
        }
    }

    private suspend fun showPayError(messageRes: Int) {
        ToastEventBus.send(
            type = Toast.ToastType.WARNING,
            title = context.getString(R.string.slashtags__error_pay_title),
            description = context.getString(messageRes),
        )
    }

    private fun observeContactUpdates() {
        viewModelScope.launch {
            pubkyRepo.contacts.collect { contacts ->
                val updated = contacts.find { it.publicKey == publicKey } ?: return@collect
                _uiState.update {
                    it.copy(
                        profile = updated,
                        tags = updated.tags.toImmutableList(),
                    )
                }
            }
        }
    }

    fun copyPublicKey() {
        context.setClipboardText(publicKey, context.getString(R.string.profile__public_key))
        viewModelScope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
            )
        }
    }

    fun showAddTagSheet() {
        _uiState.update { it.copy(showAddTagSheet = true) }
    }

    fun dismissAddTagSheet() {
        _uiState.update { it.copy(showAddTagSheet = false) }
    }

    fun addTag(tag: String) {
        val newTags = (_uiState.value.tags + tag).distinct().toImmutableList()
        _uiState.update { it.copy(tags = newTags, showAddTagSheet = false) }
        persistTags(newTags)
    }

    fun removeTag(index: Int) {
        val newTags = _uiState.value.tags.filterIndexed { i, _ -> i != index }.toImmutableList()
        _uiState.update { it.copy(tags = newTags) }
        persistTags(newTags)
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteDialog() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun deleteContact() {
        viewModelScope.launch {
            _uiState.update { it.copy(showDeleteDialog = false) }
            pubkyRepo.removeContact(publicKey)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.contacts__delete_success),
                        testTag = "ContactDeletedToast",
                    )
                    _effects.emit(ContactDetailEffect.DeleteSuccess)
                }
                .onFailure {
                    Logger.error("Failed to delete contact '$publicKey'", it, context = TAG)
                }
        }
    }

    private fun persistTags(tags: List<String>) {
        val profile = _uiState.value.profile ?: return
        viewModelScope.launch {
            pubkyRepo.updateContact(
                publicKey = publicKey,
                name = profile.name,
                bio = profile.bio,
                imageUrl = profile.imageUrl,
                links = profile.links.map { PubkyProfileLink(it.label, it.url) },
                tags = tags,
            ).onFailure {
                Logger.error("Failed to update tags for contact '$publicKey'", it, context = TAG)
            }
        }
    }
}

@Stable
data class ContactDetailUiState(
    val profile: PubkyProfile? = null,
    val tags: ImmutableList<String> = persistentListOf(),
    val isLoading: Boolean = false,
    val hasPublicPaymentEndpoint: Boolean = false,
    val showAddTagSheet: Boolean = false,
    val showDeleteDialog: Boolean = false,
)

sealed interface ContactDetailEffect {
    data object DeleteSuccess : ContactDetailEffect
    data class OpenPayment(val paymentRequest: String, val publicKey: String) : ContactDetailEffect
}
