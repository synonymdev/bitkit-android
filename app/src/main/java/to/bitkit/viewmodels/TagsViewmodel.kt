package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import to.bitkit.services.CoreService
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class TagsViewmodel @Inject constructor(
    private val coreService: CoreService,
): ViewModel() {

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
}
