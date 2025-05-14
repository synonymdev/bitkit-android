package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.services.CoreService
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class TagsViewModel @Inject constructor(
    private val coreService: CoreService,
): ViewModel() {

    private val _uiState = MutableStateFlow(AddTagUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadTagSuggestions()
    }

    fun addTags(activityId: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                coreService.activity.appendTags(toActivityId = activityId, tags = tags)
            } catch (e: Exception) {
                Logger.error("Failed to add tags to activity", e)
            }
        }
    }

    fun addTag(activityId: String, tag: String) {
        addTags(activityId = activityId, tags = listOf(tag))
    }

    fun onInputUpdated(input: String) {
        _uiState.update { it.copy(tagInput = input) }
    }

    fun loadTagSuggestions() {
        viewModelScope.launch(Dispatchers.IO) {
            val tags = coreService.activity.allPossibleTags()
            _uiState.update { it.copy(tagsSuggestions = tags) }
        }
    }
}

data class AddTagUiState(
    val tagsSuggestions: List<String> = listOf(),
    val tagInput: String = ""
)
