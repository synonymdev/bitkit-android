package to.bitkit.ui.screens.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import to.bitkit.data.SettingsStore
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.models.toSuggestionOrNull
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.toArticleModel
import to.bitkit.models.widget.toBlockModel
import to.bitkit.repositories.CurrencyRepo
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WidgetsRepo
import to.bitkit.ui.screens.widgets.blocks.toWeatherModel
import java.math.BigDecimal
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val walletRepo: WalletRepo,
    private val widgetsRepo: WidgetsRepo,
    private val settingsStore: SettingsStore,
    private val currencyRepo: CurrencyRepo
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _currentArticle = MutableStateFlow<ArticleModel?>(null)
    private val _currentFact = MutableStateFlow<String?>(null)

    init {
        setupStateObservation()
        setupArticleRotation()
        setupFactRotation()
        checkHighBalance()
    }

    private fun setupStateObservation() {
        viewModelScope.launch {
            combine(
                createSuggestionsFlow(),
                settingsStore.data,
                widgetsRepo.widgetsDataFlow,
                _currentArticle,
                _currentFact,
            ) { suggestions, settings, widgetsData, currentArticle, currentFact ->
                _uiState.value.copy(
                    suggestions = suggestions,
                    showWidgets = settings.showWidgets,
                    showWidgetTitles = settings.showWidgetTitles,
                    widgetsWithPosition = widgetsData.widgets,
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
                )
            }.collect { newState ->
                _uiState.update { newState }
            }
        }

        viewModelScope.launch {
            combine(
                settingsStore.data,
                walletRepo.balanceState
            ) { settings, balanceState ->
                _uiState.value.copy(
                    showEmptyState = settings.showEmptyState && balanceState.totalSats == 0uL
                )
            }.collect { newState ->
                _uiState.update { newState }
            }
        }
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
                    _currentArticle.value = null
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
                    _currentFact.value = null
                }
            }
        }
    }

    private suspend fun startArticleRotation(articlesList: List<ArticleModel>) {
        while (_uiState.value.showWidgets && articlesList.isNotEmpty()) {
            _currentArticle.value = articlesList.randomOrNull()
            delay(30.seconds)
        }
        _currentArticle.value = null
    }

    private suspend fun startFactsRotation(factList: List<String>) {
        while (_uiState.value.showWidgets && factList.isNotEmpty()) {
            _currentFact.value = factList.randomOrNull()
            delay(20.seconds)
        }
        _currentFact.value = null
    }

    private fun checkHighBalance() {
        viewModelScope.launch {
            delay(CHECK_DELAY_MILLISECONDS)

            val settings = settingsStore.data.first()

            val totalOnChainSats = walletRepo.balanceState.value.totalSats
            val balanceUsd = satsToUsd(totalOnChainSats) ?: return@launch
            val thresholdReached = balanceUsd > BigDecimal(BALANCE_THRESHOLD_USD)
            val isTimeOutOver = settings.lastTimeAskedBalanceWarningMillis - ASK_INTERVAL_MILLIS > ASK_INTERVAL_MILLIS
            val belowMaxWarnings = settings.balanceWarningTimes < MAX_WARNINGS

            if (thresholdReached && isTimeOutOver && belowMaxWarnings && !_uiState.value.highBalanceSheetVisible) {
                settingsStore.update {
                    it.copy(
                        balanceWarningTimes = it.balanceWarningTimes + 1,
                        lastTimeAskedBalanceWarningMillis = Clock.System.now().toEpochMilliseconds()
                    )
                }
                _uiState.update { it.copy(highBalanceSheetVisible = true) }
            }

            if (!thresholdReached) {
                settingsStore.update {
                    it.copy(
                        balanceWarningTimes = 0,
                    )
                }
            }
        }
    }

    private fun satsToUsd(sats: ULong): BigDecimal? {
        val converted = currencyRepo.convertSatsToFiat(sats = sats.toLong(), currency = "USD").getOrNull()
        return converted?.value
    }

    fun dismissEmptyState() {
        viewModelScope.launch {
            settingsStore.update { it.copy(showEmptyState = false) }
        }
    }

    fun dismissHighBalanceSheet() {
        _uiState.update { it.copy(highBalanceSheetVisible = false) }
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

    fun enableEditMode() {
        _uiState.update { it.copy(isEditingWidgets = true) }
    }

    fun disableEditMode() {
        _uiState.update { it.copy(isEditingWidgets = false) }
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

            _uiState.update { it.copy(widgetsWithPosition = updatedWidgets) }
        }
    }

    fun confirmWidgetOrder() {
        viewModelScope.launch {
            val widgets = _uiState.value.widgetsWithPosition
            widgetsRepo.updateWidgets(widgets)
            disableEditMode()
        }
    }

    fun deleteWidget(widgetType: WidgetType) {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(widgetType)
            dismissAlertDeleteWidget()
        }
    }

    fun displayAlertDeleteWidget(widgetType: WidgetType) {
        viewModelScope.launch {
            _uiState.update { it.copy(deleteWidgetAlert = widgetType) }
        }
    }

    fun dismissAlertDeleteWidget() {
        viewModelScope.launch {
            _uiState.update { it.copy(deleteWidgetAlert = null) }
        }
    }


    private fun createSuggestionsFlow() = combine(
        walletRepo.balanceState,
        settingsStore.data,
    ) { balanceState, settings ->
        val baseSuggestions = when {
            balanceState.totalLightningSats > 0uL -> { // With Lightning
                listOfNotNull(
                    Suggestion.BACK_UP.takeIf { !settings.backupVerified },
                    Suggestion.SECURE.takeIf { !settings.isPinEnabled },
                    Suggestion.BUY,
                    Suggestion.SUPPORT,
                    Suggestion.INVITE,
                    Suggestion.QUICK_PAY,
                    Suggestion.SHOP,
                    Suggestion.PROFILE,
                )
            }

            balanceState.totalOnchainSats > 0uL -> { // Only on chain balance
                listOfNotNull(
                    Suggestion.BACK_UP.takeIf { !settings.backupVerified },
                    Suggestion.SPEND,
                    Suggestion.SECURE.takeIf { !settings.isPinEnabled },
                    Suggestion.BUY,
                    Suggestion.SUPPORT,
                    Suggestion.INVITE,
                    Suggestion.SHOP,
                    Suggestion.PROFILE,
                )
            }

            else -> { // Empty wallet
                listOfNotNull(
                    Suggestion.BUY,
                    Suggestion.SPEND,
                    Suggestion.BACK_UP.takeIf { !settings.backupVerified },
                    Suggestion.SECURE.takeIf { !settings.isPinEnabled },
                    Suggestion.SUPPORT,
                    Suggestion.INVITE,
                    Suggestion.PROFILE,
                )
            }
        }
        // TODO REMOVE PROFILE CARD IF THE USER ALREADY HAS one
        val dismissedList = settings.dismissedSuggestions.mapNotNull { it.toSuggestionOrNull() }
        baseSuggestions.filterNot { it in dismissedList }
    }

    companion object {
        /**How high the balance must be to show this warning to the user (in USD)*/
        private const val BALANCE_THRESHOLD_USD = 500L
        private const val MAX_WARNINGS = 3

        /** 1 day - how long this prompt will be hidden if user taps Later*/
        private const val ASK_INTERVAL_MILLIS = 1000 * 60 * 60 * 24

        /**How long user needs to stay on the home screen before he will see this prompt*/
        private const val CHECK_DELAY_MILLISECONDS = 2500L
    }
}
