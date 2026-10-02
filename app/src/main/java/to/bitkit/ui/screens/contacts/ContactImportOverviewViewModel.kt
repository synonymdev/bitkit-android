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
import kotlinx.coroutines.flow.drop
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
class ContactImportOverviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {

    companion object {
        private const val TAG = "ContactImportOverviewVM"
    }

    private val _uiState = MutableStateFlow(ContactImportOverviewUiState())
    val uiState: StateFlow<ContactImportOverviewUiState> = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<ContactImportOverviewEffect>(extraBufferCapacity = 1)
    val effects = _effects.asSharedFlow()

    private var hasLeft = false

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
                    profile = profile,
                    contacts = contacts.toImmutableList(),
                )
            }
        }
        viewModelScope.launch {
            pubkyRepo.isImportingContacts.collect { isImporting ->
                _uiState.update { it.copy(isImporting = isImporting) }
            }
        }
        viewModelScope.launch {
            pubkyRepo.contactImportVersion.drop(1).collect { completeImport() }
        }
    }

    fun importAll() {
        if (_uiState.value.isImporting) return
        val contacts = _uiState.value.contacts
        if (contacts.isEmpty()) return

        _uiState.update { it.copy(isImporting = true) }
        viewModelScope.launch {
            try {
                pubkyRepo.importContacts(contacts)
                    .onSuccess { completeImport() }
                    .onFailure {
                        Logger.error("Failed to import all contacts", it, context = TAG)
                        ToastEventBus.send(
                            type = Toast.ToastType.ERROR,
                            title = context.getString(R.string.common__error),
                            description = it.message,
                        )
                    }
            } finally {
                _uiState.update { it.copy(isImporting = false) }
            }
        }
    }

    fun navigateToSelect() {
        if (_uiState.value.isImporting) return
        viewModelScope.launch {
            _effects.emit(ContactImportOverviewEffect.NavigateToSelect)
        }
    }

    fun onBackClick() {
        hasLeft = true
        viewModelScope.launch {
            pubkyRepo.discardPendingImport()
            _effects.emit(ContactImportOverviewEffect.NavigateBack)
        }
    }

    private fun completeImport() {
        if (hasLeft) return
        _uiState.update { it.copy(shouldRedirectToPayContacts = true) }
    }
}

@Stable
data class ContactImportOverviewUiState(
    val profile: PubkyProfile? = null,
    val contacts: ImmutableList<PubkyProfile> = persistentListOf(),
    val isImporting: Boolean = false,
    val shouldRedirectToPayContacts: Boolean = false,
)

sealed interface ContactImportOverviewEffect {
    data object NavigateToSelect : ContactImportOverviewEffect
    data object NavigateBack : ContactImportOverviewEffect
}
