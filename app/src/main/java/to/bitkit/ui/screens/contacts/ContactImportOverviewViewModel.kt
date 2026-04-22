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
    }

    fun importAll() {
        val contacts = _uiState.value.contacts
        if (contacts.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isImporting = true) }
            pubkyRepo.importContacts(contacts.map { it.publicKey })
                .onSuccess {
                    pubkyRepo.clearPendingImport()
                    _uiState.update { it.copy(isImporting = false) }
                    _effects.emit(ContactImportOverviewEffect.ImportComplete)
                }
                .onFailure {
                    Logger.error("Failed to import all contacts", it, context = TAG)
                    _uiState.update { it.copy(isImporting = false) }
                    ToastEventBus.send(
                        type = Toast.ToastType.ERROR,
                        title = context.getString(R.string.common__error),
                        description = it.message,
                    )
                }
        }
    }

    fun navigateToSelect() {
        viewModelScope.launch {
            _effects.emit(ContactImportOverviewEffect.NavigateToSelect)
        }
    }

    fun onBackClick() {
        viewModelScope.launch {
            pubkyRepo.clearPendingImport()
            _effects.emit(ContactImportOverviewEffect.NavigateBack)
        }
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
    data object ImportComplete : ContactImportOverviewEffect
    data object NavigateToSelect : ContactImportOverviewEffect
    data object NavigateBack : ContactImportOverviewEffect
}
