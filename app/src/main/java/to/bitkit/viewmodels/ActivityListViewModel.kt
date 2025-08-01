package to.bitkit.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synonym.bitkitcore.Activity
import com.synonym.bitkitcore.ActivityFilter
import com.synonym.bitkitcore.PaymentType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import to.bitkit.di.BgDispatcher
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.CoreService
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ActivityListViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val coreService: CoreService,
    private val lightningRepo: LightningRepo,
    private val ldkNodeEventBus: LdkNodeEventBus,
    private val activityRepo: ActivityRepo
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
    val endDate = _endDate.asStateFlow()

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

    private var isClearingFilters = false

    private val _selectedTab = MutableStateFlow(ActivityTab.ALL)
    val selectedTab = _selectedTab.asStateFlow()

    fun setTab(tab: ActivityTab) {
        _selectedTab.value = tab
        viewModelScope.launch(bgDispatcher) {
            updateFilteredActivities()
        }
    }

    init {
        viewModelScope.launch(bgDispatcher) {
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
        viewModelScope.launch(bgDispatcher) {
            _searchText
                .debounce(300)
                .collect {
                    if (!isClearingFilters) {
                        updateFilteredActivities()
                    }
                }
        }
    }

    private fun observeDateRange() {
        viewModelScope.launch(bgDispatcher) {
            combine(_startDate, _endDate) { _, _ -> }
                .collect {
                    if (!isClearingFilters) {
                        updateFilteredActivities()
                    }
                }
        }
    }

    private fun observeSelectedTags() {
        viewModelScope.launch(bgDispatcher) {
            _selectedTags.collect {
                if (!isClearingFilters) {
                    updateFilteredActivities()
                }
            }
        }
    }

    private fun syncState() {
        viewModelScope.launch(bgDispatcher) {
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

    private suspend fun updateFilteredActivities() = withContext(bgDispatcher) {
        try {
            var txType: PaymentType? = when (_selectedTab.value) {
                ActivityTab.SENT -> PaymentType.SENT
                ActivityTab.RECEIVED -> PaymentType.RECEIVED
                else -> null
            }

            val activities = coreService.activity.get(
                filter = ActivityFilter.ALL,
                txType = txType,
                tags = _selectedTags.value.takeIf { it.isNotEmpty() }?.toList(),
                search = _searchText.value.takeIf { it.isNotEmpty() },
                minDate = _startDate.value?.let { it / 1000 }?.toULong(),
                maxDate = _endDate.value?.let { it / 1000 }?.toULong(),
            )

            _filteredActivities.value = when (_selectedTab.value) {
                ActivityTab.OTHER -> activities.filter { it is Activity.Onchain && it.v1.isTransfer }
                else -> activities
            }
        } catch (e: Exception) {
            Logger.error("Failed to filter activities", e)
        }
    }

    fun updateAvailableTags() {
        viewModelScope.launch(bgDispatcher) {
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

    fun clearFilters() {
        viewModelScope.launch(bgDispatcher) {
            try {
                isClearingFilters = true

                _searchText.value = ""
                _selectedTags.value = emptySet()
                _startDate.value = null
                _endDate.value = null
                _selectedTab.value = ActivityTab.ALL

                updateFilteredActivities()
            } finally {
                isClearingFilters = false
            }
        }
    }

    fun syncLdkNodePayments() {
        viewModelScope.launch {
            activityRepo.syncActivities().onSuccess {
                syncState()
            }.onFailure { e ->
                Logger.error("Failed to sync ldk-node payments", e)
            }
        }
    }

    fun generateRandomTestData(count: Int) {
        viewModelScope.launch(bgDispatcher) {
            coreService.activity.generateRandomTestData(count)
            syncState()
        }
    }

    fun removeAllActivities() {
        viewModelScope.launch(bgDispatcher) {
            coreService.activity.removeAll()
            syncState()
        }
    }
}
