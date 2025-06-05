package to.bitkit.ui.screens.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.models.toSuggestionOrNull
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.toArticleModel
import to.bitkit.models.widget.toBlockModel
import to.bitkit.repositories.WalletRepo
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val appStorage: AppStorage,
    private val walletRepo: WalletRepo,
    private val widgetsRepo: WidgetsRepo,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _currentArticle = MutableStateFlow<ArticleModel?>(null)
    private val _currentFact = MutableStateFlow<String?>(null)

    init {
        setupStateObservation()
        setupArticleRotation()
        setupFactRotation()
    }

    private fun setupStateObservation() {
        viewModelScope.launch {
            combine(
                createSuggestionsFlow(),
                settingsStore.data,
                widgetsRepo.widgetsDataFlow,
                _currentArticle,
                _currentFact
            ) { suggestions, settings, widgetsData, currentArticle, currentFact ->
                HomeUiState(
                    suggestions = suggestions,
                    showWidgets = settings.showWidgets,
                    showWidgetTitles = settings.showWidgetTitles,
                    widgetsWithPosition = widgetsData.widgets,
                    headlinePreferences = widgetsData.headlinePreferences,
                    factsPreferences = widgetsData.factsPreferences,
                    blocksPreferences = widgetsData.blocksPreferences,
                    currentArticle = currentArticle,
                    currentFact = currentFact,
                    currentBlock = widgetsData.block?.toBlockModel()
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

    fun removeSuggestion(suggestion: Suggestion) {
        viewModelScope.launch {
            settingsStore.addDismissedSuggestion(suggestion)
        }
    }

    fun refreshWidgets() {
        viewModelScope.launch {
            widgetsRepo.refreshAllWidgets()
        }
    }

    fun refreshSpecificWidget(widgetType: WidgetType) {
        viewModelScope.launch {
            widgetsRepo.refreshWidget(widgetType)
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
}
