package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ext.setClipboardText
import to.bitkit.models.PubkyProfile
import to.bitkit.models.Toast
import to.bitkit.repositories.PubkyRepo
import to.bitkit.ui.shared.toast.ToastEventBus
import javax.inject.Inject

@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val publicKey: String = checkNotNull(
        savedStateHandle["publicKey"],
    ) { "publicKey not found in SavedStateHandle" }

    private val _uiState = MutableStateFlow(ContactDetailUiState())
    val uiState: StateFlow<ContactDetailUiState> = _uiState.asStateFlow()

    init {
        loadContact()
    }

    fun loadContact() {
        viewModelScope.launch {
            val cached = pubkyRepo.contacts.value.find { it.publicKey == publicKey }
            _uiState.update { it.copy(isLoading = true, profile = cached) }
            pubkyRepo.fetchContactProfile(publicKey)
                .onSuccess { profile ->
                    _uiState.update { it.copy(profile = profile, isLoading = false) }
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

    fun copyPublicKey() {
        context.setClipboardText(publicKey, context.getString(R.string.profile__public_key))
        viewModelScope.launch {
            ToastEventBus.send(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
            )
        }
    }
}

data class ContactDetailUiState(
    val profile: PubkyProfile? = null,
    val isLoading: Boolean = false,
)
