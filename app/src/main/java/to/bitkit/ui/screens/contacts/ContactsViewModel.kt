package to.bitkit.ui.screens.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.PubkyProfile
import to.bitkit.repositories.PubkyRepo
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val pubkyRepo: PubkyRepo,
) : ViewModel() {

    private val _searchText = MutableStateFlow("")

    val uiState: StateFlow<ContactsUiState> = combine(
        pubkyRepo.contacts,
        pubkyRepo.isLoadingContacts,
        pubkyRepo.profile,
        _searchText,
    ) { contacts, isLoading, myProfile, search ->
        val filtered = if (search.isBlank()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(search, ignoreCase = true) ||
                    it.publicKey.contains(search, ignoreCase = true)
            }
        }
        val grouped = filtered.groupBy { it.name.firstOrNull()?.uppercaseChar() ?: '#' }
            .toSortedMap()
        ContactsUiState(
            groupedContacts = grouped,
            myProfile = myProfile,
            isLoading = isLoading,
            searchText = search,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ContactsUiState())

    fun onSearchTextChange(text: String) {
        _searchText.update { text.trim() }
    }

    fun refresh() {
        viewModelScope.launch { pubkyRepo.loadContacts() }
    }
}

data class ContactsUiState(
    val groupedContacts: Map<Char, List<PubkyProfile>> = emptyMap(),
    val myProfile: PubkyProfile? = null,
    val isLoading: Boolean = false,
    val searchText: String = "",
) {
    val isEmpty: Boolean get() = groupedContacts.isEmpty() && !isLoading
}
