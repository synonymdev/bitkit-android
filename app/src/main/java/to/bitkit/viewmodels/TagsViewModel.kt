package to.bitkit.viewmodels

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.SettingsStore
import javax.inject.Inject

@HiltViewModel
class TagsViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddTagUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadTagSuggestions()
    }

    fun onInputUpdated(input: String) {
        _uiState.update { it.copy(tagInput = input) }
    }

    fun loadTagSuggestions() {
        viewModelScope.launch(Dispatchers.IO) {
            val tags = settingsStore.data.first().lastUsedTags
            _uiState.update { it.copy(tagsSuggestions = tags.toImmutableList()) }
        }
    }
}

@Immutable
data class AddTagUiState(
    val tagsSuggestions: ImmutableList<String> = persistentListOf(),
    val tagInput: String = ""
)
