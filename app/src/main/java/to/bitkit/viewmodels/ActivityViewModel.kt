package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.utils.Logger
import uniffi.bitkitcore.Activity
import uniffi.bitkitcore.ActivityFilter
import javax.inject.Inject

@HiltViewModel
class ActivityListViewModel @Inject constructor(
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
    private val ldkNodeEventBus: LdkNodeEventBus,
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
        viewModelScope.launch {
            ldkNodeEventBus.events.collect {
                // TODO: sync only on specific events for better performance
                syncLdkNodePayments()
            }
        }

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
                _latestActivities.value = coreService.activity.get(filter = ActivityFilter.ALL, limit = limitLatest)

                // Fetch lightning and onchain activities
                _lightningActivities.value = coreService.activity.get(filter = ActivityFilter.LIGHTNING)
                _onchainActivities.value = coreService.activity.get(filter = ActivityFilter.ONCHAIN)

                // Fetch filtered activities and available tags
                updateFilteredActivities()
                updateAvailableTags()
            } catch (e: Exception) {
                Logger.error("Failed to sync activities", e)
            }
        }
    }

    private suspend fun updateFilteredActivities() {
        try {
            _filteredActivities.value = coreService.activity.get(
                filter = ActivityFilter.ALL,
                tags = if (_selectedTags.value.isEmpty()) null else _selectedTags.value.toList(),
                search = if (_searchText.value.isEmpty()) null else _searchText.value,
                minDate = _startDate.value?.toULong(),
                maxDate = _endDate.value?.toULong(),
            )
        } catch (e: Exception) {
            Logger.error("Failed to filter activities", e)
        }
    }

    private fun updateAvailableTags() {
        viewModelScope.launch {
            try {
                _availableTags.value = coreService.activity.allPossibleTags()
            } catch (e: Exception) {
                Logger.error("Failed to get available tags", e)
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

    var isSyncingLdkNodePayments = false
    fun syncLdkNodePayments() {
        if (isSyncingLdkNodePayments) {
            Logger.warn("LDK-node payments are already being synced, skipping")
            return
        }

        viewModelScope.launch {
            isSyncingLdkNodePayments = true
            lightningRepo.getPayments()
                .onSuccess {
                    coreService.activity.syncLdkNodePayments(it)
                    syncState()
                }.onFailure { e ->
                    Logger.error("Failed to sync ldk-node payments", e)
                }
            isSyncingLdkNodePayments = false
        }
    }

    fun addTags(activityId: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                coreService.activity.appendTags(toActivityId = activityId, tags = tags)
                syncState()
            } catch (e: Exception) {
                Logger.error("Failed to add tags to activity", e)
            }
        }
    }

    fun removeTags(activityId: String, tags: List<String>) {
        viewModelScope.launch {
            try {
                coreService.activity.dropTags(fromActivityId = activityId, tags = tags)
                syncState()
            } catch (e: Exception) {
                Logger.error("Failed to remove tags from activity", e)
            }
        }
    }

    suspend fun getActivitiesWithTag(tag: String): List<Activity> {
        return try {
            coreService.activity.get(tags = listOf(tag))
        } catch (e: Exception) {
            Logger.error("Failed get activities by tag", e)
            emptyList()
        }
    }

    fun generateRandomTestData() {
        viewModelScope.launch {
            coreService.activity.generateRandomTestData()
            syncState()
        }
    }

    fun removeAllActivities() {
        viewModelScope.launch {
            coreService.activity.removeAll()
            syncState()
        }
    }
}
