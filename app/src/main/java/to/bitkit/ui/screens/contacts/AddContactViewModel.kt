package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class AddContactViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    companion object {
        private const val TAG = "AddContactViewModel"
    }

    private val publicKey: String = checkNotNull(
        savedStateHandle["publicKey"],
    ) { "publicKey not found in SavedStateHandle" }

    private val _uiState = MutableStateFlow(AddContactUiState(publicKeyInput = publicKey))
    val uiState: StateFlow<AddContactUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<AddContactEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        fetchProfile(publicKey)
    }

    fun onPublicKeyChange(value: String) {
        _uiState.update { it.copy(publicKeyInput = value, error = null) }
    }

    fun fetchProfile(publicKey: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, publicKeyInput = publicKey) }
            pubkyRepo.fetchContactProfile(publicKey)
                .onSuccess { profile ->
                    _uiState.update { it.copy(fetchedProfile = profile, isLoading = false) }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = context.getString(R.string.contacts__add_error_fetch),
                        )
                    }
                }
        }
    }

    fun saveContact() {
        val profile = _uiState.value.fetchedProfile ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            pubkyRepo.addContact(profile.publicKey, profile)
                .onSuccess {
                    ToastEventBus.send(
                        type = Toast.ToastType.SUCCESS,
                        title = context.getString(R.string.contacts__add_contact_saved),
                    )
                    _effects.emit(AddContactEffect.ContactSaved)
                }
                .onFailure {
                    Logger.error("Failed to save contact", it, context = TAG)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = context.getString(R.string.common__error_body),
                        )
                    }
                }
        }
    }
}

@Stable
data class AddContactUiState(
    val publicKeyInput: String = "",
    val fetchedProfile: PubkyProfile? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface AddContactEffect {
    data object ContactSaved : AddContactEffect
}
