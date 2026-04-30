package to.bitkit.ui.screens.contacts

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.PubkyProfile
import to.bitkit.models.PubkyPublicKeyFormat
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.PubkyRepo
import javax.inject.Inject

@HiltViewModel
class ContactActivityViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityRepo: ActivityRepo,
    private val pubkyRepo: PubkyRepo,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val publicKey: String = checkNotNull(
        savedStateHandle["publicKey"],
    ) { "publicKey not found in SavedStateHandle" }

    private val _uiState = MutableStateFlow(ContactActivityUiState())
    val uiState: StateFlow<ContactActivityUiState> = _uiState.asStateFlow()

    init {
        loadContact()
        loadActivities()
        observeContactUpdates()
        observeActivityUpdates()
    }

    fun loadActivities() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            activityRepo.contactActivities(publicKey)
                .onSuccess { activities ->
                    _uiState.update {
                        it.copy(
                            activities = activities.toImmutableList(),
                            isLoading = false,
                            error = null,
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = context.getString(R.string.wallet__activity_error_load_failed),
                        )
                    }
                }
        }
    }

    private fun loadContact() {
        viewModelScope.launch {
            pubkyRepo.contacts.value.matchingContact()?.let { cached ->
                _uiState.update { it.copy(profile = cached) }
                return@launch
            }
            pubkyRepo.fetchContactProfile(publicKey)
                .onSuccess { profile -> _uiState.update { it.copy(profile = profile) } }
                .onFailure {
                    _uiState.update { it.copy(profile = PubkyProfile.placeholder(publicKey)) }
                }
        }
    }

    private fun observeContactUpdates() {
        viewModelScope.launch {
            pubkyRepo.contacts.collect { contacts ->
                contacts.matchingContact()?.let { profile ->
                    _uiState.update { it.copy(profile = profile) }
                }
            }
        }
    }

    private fun observeActivityUpdates() {
        viewModelScope.launch {
            activityRepo.activitiesChanged.drop(1).collect {
                loadActivities()
            }
        }
    }

    private fun List<PubkyProfile>.matchingContact(): PubkyProfile? {
        val normalizedKey = PubkyPublicKeyFormat.normalized(publicKey) ?: publicKey
        return firstOrNull { PubkyPublicKeyFormat.matches(it.publicKey, normalizedKey) }
    }
}

@Stable
data class ContactActivityUiState(
    val profile: PubkyProfile? = null,
    val activities: ImmutableList<Activity>? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)
