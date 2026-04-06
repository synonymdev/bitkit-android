package to.bitkit.ui.screens.wallets

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.data.SettingsData
import to.bitkit.data.SettingsStore
import to.bitkit.data.entities.TransferEntity
import to.bitkit.models.ActivityBannerType
import to.bitkit.models.BannerItem
import to.bitkit.models.Suggestion
import to.bitkit.models.TransferType
import to.bitkit.models.WidgetType
import to.bitkit.models.toSuggestionOrNull
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.toArticleModel
import to.bitkit.models.widget.toBlockModel
import to.bitkit.repositories.ActivityRepo
import to.bitkit.repositories.PubkyRepo
import to.bitkit.repositories.TransferRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@Suppress("TooManyFunctions", "LongParameterList")
@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val walletRepo: WalletRepo,
    private val widgetsRepo: WidgetsRepo,
    private val settingsStore: SettingsStore,
    private val transferRepo: TransferRepo,
    private val pubkyRepo: PubkyRepo,
    private val activityRepo: ActivityRepo,
) : ViewModel() {

    companion object {
        private const val MAX_SUGGESTIONS = 4
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val profileDisplayName = pubkyRepo.displayName
    val profileDisplayImageUri = pubkyRepo.displayImageUri

    private val _currentArticle = MutableStateFlow<ArticleModel?>(null)
    private val _currentFact = MutableStateFlow<String?>(null)

    init {
        setupStateObservation()
        setupArticleRotation()
        setupFactRotation()
    }

    @Suppress("LongMethod")
    private fun setupStateObservation() {
        viewModelScope.launch {
            combine(
                settingsStore.data,
                widgetsRepo.widgetsDataFlow,
                _currentArticle,
                _currentFact,
            ) { settings, widgetsData, currentArticle, currentFact ->
                _uiState.update {
                    it.copy(
                        showWidgets = settings.showWidgets,
                        showWidgetTitles = settings.showWidgetTitles,
                        widgetsWithPosition = if (it.isEditingWidgets &&
                            it.widgetsWithPosition.size == widgetsData.widgets.size
                        ) {
                            it.widgetsWithPosition
                        } else {
                            widgetsData.widgets.toImmutableList()
                        },
                        headlinePreferences = widgetsData.headlinePreferences,
                        factsPreferences = widgetsData.factsPreferences,
                        blocksPreferences = widgetsData.blocksPreferences,
                        weatherPreferences = widgetsData.weatherPreferences,
                        pricePreferences = widgetsData.pricePreferences,
                        currentArticle = currentArticle,
                        currentFact = currentFact,
                        currentBlock = widgetsData.block?.toBlockModel(),
                        currentWeather = widgetsData.weather?.toWeatherModel(),
                        currentPrice = widgetsData.price,
                        showWidgetsOnboardingHint = settings.showWidgets &&
                            !settings.widgetsOnboardingHintDismissed,
                    )
                }
            }.collect()
        }

        viewModelScope.launch {
            createSuggestionsFlow().collect { suggestions ->
                _uiState.update { it.copy(suggestions = suggestions.toImmutableList()) }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        val hasActivityFlow = activityRepo.activitiesChanged.mapLatest {
            activityRepo.getActivities(limit = 1u).getOrNull()?.isNotEmpty() == true
        }

        viewModelScope.launch {
            combine(
                settingsStore.data,
                walletRepo.balanceState,
                transferRepo.activeTransfers,
                hasActivityFlow,
            ) { settings, balanceState, activeTransfers, hasActivity ->
                _uiState.update {
                    it.copy(
                        showEmptyState = settings.showEmptyBalanceView &&
                            !hasActivity &&
                            balanceState.totalSats == 0uL &&
                            balanceState.balanceInTransferToSpending == 0uL &&
                            balanceState.balanceInTransferToSavings == 0uL &&
                            activeTransfers.isEmpty()
                    )
                }
            }.collect()
        }
        viewModelScope.launch { createBannersFlow() }
    }

    private fun setupArticleRotation() {
        viewModelScope.launch {
            combine(
                widgetsRepo.articlesFlow.map { articles -> articles.map { it.toArticleModel() } },
                settingsStore.data.map { it.showWidgets }
            ) { articlesList, showWidgets ->
                Pair(articlesList, showWidgets)
            }.collect { (articlesList, showWidgets) ->
                if (showWidgets && articlesList.isNotEmpty()) {
                    startArticleRotation(articlesList)
                } else {
                    _currentArticle.update { null }
                }
            }
        }
    }

    private fun setupFactRotation() {
        viewModelScope.launch {
            combine(
                widgetsRepo.factsFlow,
                settingsStore.data.map { it.showWidgets }
            ) { factList, showWidgets ->
                Pair(factList, showWidgets)
            }.collect { (factList, showWidgets) ->
                if (showWidgets && factList.isNotEmpty()) {
                    startFactsRotation(factList = factList)
                } else {
                    _currentFact.update { null }
                }
            }
        }
    }

    private suspend fun startArticleRotation(articlesList: List<ArticleModel>) {
        while (_uiState.value.showWidgets && articlesList.isNotEmpty()) {
            _currentArticle.update { articlesList.randomOrNull() }
            delay(30.seconds)
        }
        _currentArticle.update { null }
    }

    private suspend fun startFactsRotation(factList: List<String>) {
        while (_uiState.value.showWidgets && factList.isNotEmpty()) {
            _currentFact.update { factList.randomOrNull() }
            delay(20.seconds)
        }
        _currentFact.update { null }
    }

    fun onPageChanged(page: Int) {
        _uiState.update { it.copy(currentPage = page) }
    }

    fun dismissWidgetsOnboardingHint() {
        viewModelScope.launch {
            settingsStore.update { it.copy(widgetsOnboardingHintDismissed = true) }
        }
    }

    fun dismissEmptyState() {
        viewModelScope.launch {
            settingsStore.update { it.copy(showEmptyBalanceView = false) }
        }
    }

    fun removeSuggestion(suggestion: Suggestion) {
        viewModelScope.launch {
            settingsStore.addDismissedSuggestion(suggestion)
        }
    }

    fun refreshWidgets() {
        viewModelScope.launch {
            widgetsRepo.refreshEnabledWidgets()
        }
    }

    fun moveWidget(fromIndex: Int, toIndex: Int) {
        val currentWidgets = _uiState.value.widgetsWithPosition.toMutableList()
        if (fromIndex in currentWidgets.indices && toIndex in currentWidgets.indices) {
            val item = currentWidgets.removeAt(fromIndex)
            currentWidgets.add(toIndex, item)

            // Update positions
            val updatedWidgets = currentWidgets.mapIndexed { index, widget ->
                widget.copy(position = index)
            }

            _uiState.update { it.copy(widgetsWithPosition = updatedWidgets.toImmutableList()) }
        }
    }

    fun onClickEditWidgetList() {
        if (_uiState.value.isEditingWidgets) {
            viewModelScope.launch {
                val widgets = _uiState.value.widgetsWithPosition
                widgetsRepo.updateWidgets(widgets)
                disableEditMode()
            }
        } else {
            enableEditMode()
        }
    }

    fun deleteWidget(widgetType: WidgetType) {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(widgetType)
            _uiState.update {
                it.copy(
                    widgetsWithPosition = it.widgetsWithPosition
                        .filterNot { widget -> widget.type == widgetType }
                        .toImmutableList(),
                    deleteWidgetAlert = null,
                )
            }
        }
    }

    fun displayAlertDeleteWidget(widgetType: WidgetType) {
        _uiState.update { it.copy(deleteWidgetAlert = widgetType) }
    }

    fun dismissAlertDeleteWidget() {
        _uiState.update { it.copy(deleteWidgetAlert = null) }
    }

    private fun enableEditMode() {
        _uiState.update { it.copy(isEditingWidgets = true) }
    }

    fun disableEditMode() {
        _uiState.update { it.copy(isEditingWidgets = false) }
    }

    private suspend fun createBannersFlow() {
        combine(
            walletRepo.balanceState,
            transferRepo.forceCloseRemainingDuration,
        ) { balanceState, remainingDuration ->
            val defaultTitle = context.getString(R.string.lightning__transfer_in_progress)
            val savingsTitle = remainingDuration?.let {
                context.getString(R.string.lightning__transfer_ready_in, it)
            } ?: defaultTitle

            listOfNotNull(
                BannerItem(
                    type = ActivityBannerType.SPENDING,
                    title = defaultTitle,
                ).takeIf { balanceState.balanceInTransferToSpending > 0uL },
                BannerItem(
                    type = ActivityBannerType.SAVINGS,
                    title = savingsTitle,
                ).takeIf { balanceState.balanceInTransferToSavings > 0uL },
            )
        }.collect { banners ->
            _uiState.update { it.copy(banners = banners.toImmutableList()) }
        }
    }

    private fun createSuggestionsFlow() = combine(
        walletRepo.balanceState,
        settingsStore.data,
        transferRepo.activeTransfers,
        pubkyRepo.isAuthenticated,
    ) { balanceState, settings, transfers, profileAuthenticated ->
        val baseSuggestions = when {
            balanceState.totalLightningSats > 0uL ->
                spendingSuggestions(settings, profileAuthenticated)
            balanceState.totalOnchainSats > 0uL ->
                savingsOnlySuggestions(settings, transfers, profileAuthenticated)
            else -> emptyWalletSuggestions(settings, transfers, profileAuthenticated)
        }
        val dismissedList = settings.dismissedSuggestions.mapNotNull { it.toSuggestionOrNull() }
        baseSuggestions.filterNot { it in dismissedList }.take(MAX_SUGGESTIONS)
    }

    private fun spendingSuggestions(
        settings: SettingsData,
        profileAuthenticated: Boolean,
    ) = listOfNotNull(
        Suggestion.QUICK_PAY.takeIf { !settings.isQuickPayEnabled },
        Suggestion.NOTIFICATIONS.takeIf { !settings.notificationsGranted },
        Suggestion.SHOP,
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.SUPPORT,
        Suggestion.INVITE,
        Suggestion.BUY,
    )

    private fun savingsOnlySuggestions(
        settings: SettingsData,
        transfers: List<TransferEntity>,
        profileAuthenticated: Boolean,
    ) = listOfNotNull(
        Suggestion.BACK_UP.takeIf { !settings.backupVerified },
        Suggestion.SECURE.takeIf { !settings.isPinEnabled },
        Suggestion.LIGHTNING.takeIf {
            transfers.all { it.type != TransferType.TO_SPENDING }
        },
        Suggestion.SUPPORT,
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.INVITE,
        Suggestion.BUY,
    )

    private fun emptyWalletSuggestions(
        settings: SettingsData,
        transfers: List<TransferEntity>,
        profileAuthenticated: Boolean,
    ) = listOfNotNull(
        Suggestion.BUY,
        Suggestion.LIGHTNING.takeIf {
            transfers.all { it.type != TransferType.TO_SPENDING }
        },
        Suggestion.SUPPORT,
        Suggestion.BACK_UP.takeIf { !settings.backupVerified },
        Suggestion.SECURE.takeIf { !settings.isPinEnabled },
        Suggestion.PROFILE.takeIf { !profileAuthenticated },
        Suggestion.INVITE,
    )
}
