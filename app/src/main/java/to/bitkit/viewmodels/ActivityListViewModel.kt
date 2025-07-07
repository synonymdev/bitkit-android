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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.di.BgDispatcher
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.LightningRepo
import to.bitkit.services.LdkNodeEventBus
import to.bitkit.ui.screens.wallets.activity.components.ActivityTab
import to.bitkit.utils.Logger
import javax.inject.Inject

@HiltViewModel
class ActivityListViewModel @Inject constructor(
    @BgDispatcher private val bgDispatcher: CoroutineDispatcher,
    private val activityRepo: ActivityRepo,
    private val lightningRepo: LightningRepo,
    private val ldkNodeEventBus: LdkNodeEventBus,
) : ViewModel() {

    val activityState = activityRepo.activityState
    val lightningActivities = activityState.map { it.lightningActivities }
    val onchainActivities = activityState.map { it.onchainActivities }
    val latestActivities = activityState.map { it.latestActivities }
    val availableTags = activityState.map { it.availableTags }

    // ViewModel-specific filter states
    private val _searchText = MutableStateFlow("")
    val searchText = _searchText.asStateFlow()

    private val _startDate = MutableStateFlow<Long?>(null)
    val startDate = _startDate.asStateFlow()

    private val _endDate = MutableStateFlow<Long?>(null)
    val endDate = _endDate.asStateFlow()

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags = _selectedTags.asStateFlow()

    private val _selectedTab = MutableStateFlow(ActivityTab.ALL)
    val selectedTab = _selectedTab.asStateFlow()

    // ViewModel-specific filtered activities state
    private val _filteredActivities = MutableStateFlow<List<Activity>>(emptyList())
    val filteredActivities = _filteredActivities.asStateFlow()

    private var isClearingFilters = false

    init {
        observeLdkNodeEvents()
        observeSearchText()
        observeDateRange()
        observeSelectedTags()
        observeSelectedTab()

        syncState()
    }

    // MARK: - Filter Management

    fun setSearchText(text: String) {
        _searchText.value = text
    }

    fun setTab(tab: ActivityTab) {
        _selectedTab.value = tab
    }

    fun toggleTag(tag: String) {
        _selectedTags.value = if (_selectedTags.value.contains(tag)) {
            _selectedTags.value - tag
        } else {
            _selectedTags.value + tag
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
        _selectedTags.value = emptySet()
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
        viewModelScope.launch(bgDispatcher) {
            lightningRepo.getPayments()
                .onSuccess { payments ->
                    activityRepo.syncActivities().onFailure { e ->
                        Logger.error("Failed to sync LDK-node payments", e)
                    }
                }
                .onFailure { e ->
                    Logger.error("Failed to get LDK-node payments", e)
                }
        }
    }

    fun updateAvailableTags() {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.getAllAvailableTags().onFailure { e ->
                Logger.error("Failed to update available tags", e)
            }
        }
    }

    private fun syncState() {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.syncAllData().onSuccess {
                // Update filtered activities after syncing base data
                updateFilteredActivities()
            }.onFailure { e ->
                Logger.error("Failed to sync activity state", e)
            }
        }
    }

    private fun updateFilteredActivities() {
        viewModelScope.launch(bgDispatcher) {

            val selectedTab = _selectedTab.value

            val txType: PaymentType? = when (selectedTab) {
                ActivityTab.SENT -> PaymentType.SENT
                ActivityTab.RECEIVED -> PaymentType.RECEIVED
                else -> null
            }

            activityRepo.getActivities(
                filter = ActivityFilter.ALL,
                txType = txType,
                tags = _selectedTags.value.takeIf { it.isNotEmpty() }?.toList(),
                search = _searchText.value.takeIf { it.isNotEmpty() },
                minDate = _startDate.value?.let { it / 1000 }?.toULong(),
                maxDate = _endDate.value?.let { it / 1000 }?.toULong(),

                ).onSuccess { activities ->
                _filteredActivities.update { activities }
            }.onFailure { e ->
                Logger.error("Failed to get filtered activities", e)
            }
        }
    }

    fun generateRandomTestData() {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.generateRandomTestData().onFailure { e ->
                Logger.error("Failed to generate test data", e)
            }
        }
    }

    fun removeAllActivities() {
        viewModelScope.launch(bgDispatcher) {
            activityRepo.removeAllActivities().onFailure { e ->
                Logger.error("Failed to remove all activities", e)
            }
        }
    }


    private fun observeLdkNodeEvents() {
        viewModelScope.launch(bgDispatcher) {
            ldkNodeEventBus.events.collect {
                // TODO: sync only on specific events for better performance
                syncLdkNodePayments()
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeSearchText() {
        viewModelScope.launch(bgDispatcher) {
            searchText
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
            combine(startDate, endDate) { _, _ -> }
                .collect {
                    if (!isClearingFilters) {
                        updateFilteredActivities()
                    }
                }
        }
    }

    private fun observeSelectedTags() {
        viewModelScope.launch(bgDispatcher) {
            selectedTags.collect {
                if (!isClearingFilters) {
                    updateFilteredActivities()
                }
            }
        }
    }

    private fun observeSelectedTab() {
        viewModelScope.launch(bgDispatcher) {
            selectedTab.collect {
                if (!isClearingFilters) {
                    updateFilteredActivities()
                }
            }
        }
    }
}
