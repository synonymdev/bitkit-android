package to.bitkit.ui.screens.contacts

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import javax.inject.Inject

@HiltViewModel
class ContactImportSelectViewModel @Inject constructor(
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ContactImportSelectUiState())
    val uiState: StateFlow<ContactImportSelectUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ContactImportSelectEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            val profile = pubkyRepo.pendingImportProfile.value
            val contacts = pubkyRepo.pendingImportContacts.value
            if (!hasPendingImport(profile, contacts)) {
                _uiState.update { it.copy(shouldRedirectToPayContacts = true) }
                return@launch
            }
            _uiState.update {
                it.copy(
                    contacts = contacts.map { profile ->
                        SelectableContact(profile = profile, isSelected = true)
                    }.toImmutableList(),
                )
            }
        }
        viewModelScope.launch {
            pubkyRepo.isImportingContacts.collect { isImporting ->
                _uiState.update { it.copy(isImporting = isImporting) }
            }
        }
    }

    fun toggleContact(publicKey: String) {
        _uiState.update { state ->
            state.copy(
                contacts = state.contacts.map {
                    if (it.profile.publicKey == publicKey) it.copy(isSelected = !it.isSelected) else it
                }.toImmutableList(),
            )
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(
                contacts = state.contacts.map { it.copy(isSelected = true) }.toImmutableList(),
            )
        }
    }

    fun selectNone() {
        _uiState.update { state ->
            state.copy(
                contacts = state.contacts.map { it.copy(isSelected = false) }.toImmutableList(),
            )
        }
    }

    fun importSelected() {
        if (_uiState.value.isImporting) return
        val selected = _uiState.value.contacts.filter { it.isSelected }
        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            try {
                if (selected.isEmpty()) {
                    pubkyRepo.clearPendingImport()
                    _effects.emit(ContactImportSelectEffect.ImportComplete)
                    return@launch
                }

                pubkyRepo.importContacts(selected.map { it.profile })
                    .onSuccess {
                        _effects.emit(ContactImportSelectEffect.ImportComplete)
                    }
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun onBackClick() {
        viewModelScope.launch {
            _effects.emit(ContactImportSelectEffect.NavigateBack)
        }
    }
}

@Stable
data class SelectableContact(
    val profile: PubkyProfile,
    val isSelected: Boolean,
)

@Stable
data class ContactImportSelectUiState(
    val contacts: ImmutableList<SelectableContact> = persistentListOf(),
    val isImporting: Boolean = false,
    val shouldRedirectToPayContacts: Boolean = false,
) {
    val selectedCount: Int get() = contacts.count { it.isSelected }
}

sealed interface ContactImportSelectEffect {
    data object ImportComplete : ContactImportSelectEffect
    data object NavigateBack : ContactImportSelectEffect
}
