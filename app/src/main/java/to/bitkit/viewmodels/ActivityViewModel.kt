package to.bitkit.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import to.bitkit.env.Tag.APP
import to.bitkit.services.ActivityListService
import to.bitkit.services.LightningService
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.ActivityFilter
import javax.inject.Inject

@HiltViewModel
class ActivityListViewModel @Inject constructor(
    private val activityService: ActivityListService,
    private val lightningService: LightningService,
) : ViewModel() {
    private val _filteredActivities = MutableStateFlow<List<Activity>?>(null)
    val filteredActivities = _filteredActivities.asStateFlow()

    private val _lightningActivities = MutableStateFlow<List<Activity>?>(null)
    val lightningActivities = _lightningActivities.asStateFlow()

    private val _onchainActivities = MutableStateFlow<List<Activity>?>(null)
    val onchainActivities = _onchainActivities.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    private val _startDate = MutableStateFlow<Long?>(null)
    val startDate = _startDate.asStateFlow()

    private val _endDate = MutableStateFlow<Long?>(null)
    // val endDate = _endDate.asStateFlow()

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags = _selectedTags.asStateFlow()

    fun toggleTag(tag: String) {
        _selectedTags.value = if (_selectedTags.value.contains(tag)) {
            _selectedTags.value - tag
        } else {
            _selectedTags.value + tag
        }
    }

    private val _latestActivities = MutableStateFlow<List<Activity>?>(null)
    val latestActivities = _latestActivities.asStateFlow()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags = _availableTags.asStateFlow()

    init {
        observeSearchText()
        observeDateRange()
        observeSelectedTags()

        syncState()
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchText() {
        viewModelScope.launch {
            _searchText
                .debounce(300)
                .collect {
                    updateFilteredActivities()
                }
        }
    }

    private fun observeDateRange() {
        viewModelScope.launch {
            combine(_startDate, _endDate) { _, _ -> }
                .collect {
                    updateFilteredActivities()
                }
        }
    }

    private fun observeSelectedTags() {
        viewModelScope.launch {
            _selectedTags.collect {
                updateFilteredActivities()
            }
        }
    }

    private fun syncState() {
        viewModelScope.launch {
            try {
                // Fetch latest activities for the home screen
                val limitLatest = 3u
                _latestActivities.value = activityService.get(filter = ActivityFilter.ALL, limit = limitLatest)

                // Fetch lightning and onchain activities
                _lightningActivities.value = activityService.get(filter = ActivityFilter.LIGHTNING)
                _onchainActivities.value = activityService.get(filter = ActivityFilter.ONCHAIN)

                // Fetch filtered activities and available tags
                updateFilteredActivities()
                updateAvailableTags()
            } catch (e: Exception) {
                Log.e(APP, "Failed to sync activities", e)
            }
        }
    }

    private suspend fun updateFilteredActivities() {
        try {
            _filteredActivities.value = activityService.get(
                filter = ActivityFilter.ALL,
                tags = if (_selectedTags.value.isEmpty()) null else _selectedTags.value.toList(),
                search = if (_searchText.value.isEmpty()) null else _searchText.value,
                minDate = _startDate.value?.toULong(),
                maxDate = _endDate.value?.toULong(),
            )
        } catch (e: Exception) {
            Log.e(APP, "Failed to filter activities", e)
        }
    }

    private fun updateAvailableTags() {
        viewModelScope.launch {
            try {
                _availableTags.value = activityService.getAllUniqueTags()
            } catch (e: Exception) {
                Log.e(APP, "Failed to get available tags", e)
                _availableTags.value = emptyList()
            }
        }
    }

    fun setDateRange(startDate: Long?, endDate: Long?) {
        _startDate.value = startDate
        _endDate.value = endDate
    }

    fun clearDateRange() {
        _startDate.value = null
        _endDate.value = null
    }

    fun clearTags() {
        _selectedTags.value = mutableSetOf()
    }

    fun syncLdkNodePayments() {
        viewModelScope.launch {
            try {
                lightningService.payments?.let {
                    activityService.syncLdkNodePayments(it)
                    syncState()
                }
            } catch (e: Exception) {
                Log.e(APP, "Failed to sync ldk-node payments", e)
            }
        }
    }

    fun addTags(activityId: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                activityService.addTags(toActivityId = activityId, tags = tags)
                syncState()
            } catch (e: Exception) {
                Log.e(APP, "Failed to add tags to activity", e)
            }
        }
    }

    fun removeTags(activityId: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                activityService.removeTags(fromActivityId = activityId, tags = tags)
                syncState()
            } catch (e: Exception) {
                Log.e(APP, "Failed to remove tags from activity", e)
            }
        }
    }

    suspend fun getActivitiesWithTag(tag: String): List<Activity> {
        return try {
            activityService.get(tags = listOf(tag))
        } catch (e: Exception) {
            Log.e(APP, "Failed get activities by tag", e)
            emptyList()
        }
    }

    fun generateRandomTestData() {
        viewModelScope.launch {
            activityService.generateRandomTestData()
            syncState()
        }
    }

    fun removeAllActivities() {
        viewModelScope.launch {
            activityService.removeAll()
            syncState()
        }
    }
}
