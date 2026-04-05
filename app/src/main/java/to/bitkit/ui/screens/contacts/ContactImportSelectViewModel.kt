package to.bitkit.ui.screens.contacts

import android.content.Context
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
class ContactImportSelectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "ContactImportSelectVM"
    }

    private val _uiState = MutableStateFlow(ContactImportSelectUiState())
    val uiState: StateFlow<ContactImportSelectUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ContactImportSelectEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            val contacts = pubkyRepo.pendingImportContacts.value
            _uiState.update {
                it.copy(
                    contacts = contacts.map { profile ->
                        SelectableContact(profile = profile, isSelected = true)
                    }.toImmutableList(),
                )
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
        val selected = _uiState.value.contacts.filter { it.isSelected }
        if (selected.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            pubkyRepo.importContacts(selected.map { it.profile.publicKey })
                .onSuccess {
                    _uiState.update { it.copy(isImporting = false) }
                    _effects.emit(ContactImportSelectEffect.ImportComplete)
                }
                .onFailure {
                    Logger.error("Failed to import selected contacts", it, context = TAG)
                    _uiState.update { it.copy(isImporting = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = it.message,
                    )
                }
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
) {
    val selectedCount: Int get() = contacts.count { it.isSelected }
}

sealed interface ContactImportSelectEffect {
    data object ImportComplete : ContactImportSelectEffect
}
