package to.bitkit.ui.screens.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.models.toSuggestionOrNull
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.toArticleModel
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

    // Suggestions flow
    val suggestions: StateFlow<List<Suggestion>> = createSuggestionsFlow()

    // Widget-related flows
    val showWidgets = settingsStore.data.map { it.showWidgets }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val showWidgetTitles = settingsStore.data.map { it.showWidgetTitles }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val widgetsWithPosition = widgetsRepo.widgetsWithPosition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val headlinePreferences = widgetsRepo.widgetsDataFlow.map { it.headlinePreferences }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HeadlinePreferences())

    private val articles: StateFlow<List<ArticleModel>> = widgetsRepo.articlesFlow
        .map { articles -> articles.map { it.toArticleModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Current article state (with rotation)
    private val _currentArticle = MutableStateFlow<ArticleModel?>(null)
    val currentArticle: StateFlow<ArticleModel?> = _currentArticle.asStateFlow()

    // Widget refresh states
    val widgetRefreshStates = widgetsRepo.refreshStates

    init {
        setupArticleRotation()
    }

    private fun setupArticleRotation() {
        viewModelScope.launch {
            combine(
                articles,
                showWidgets
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

    private suspend fun startArticleRotation(articlesList: List<ArticleModel>) {
        while (showWidgets.first() && articlesList.isNotEmpty()) {
            _currentArticle.value = articlesList.randomOrNull()
            delay(30.seconds)
        }
        _currentArticle.value = null
    }

    fun removeSuggestion(suggestion: Suggestion) {
        appStorage.addSuggestionToRemovedList(suggestion)
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

    private fun createSuggestionsFlow(): StateFlow<List<Suggestion>> {
        val removedSuggestions = appStorage.removedSuggestionsFlow
            .map { stringList -> stringList.mapNotNull { it.toSuggestionOrNull() } }

        return combine(
            walletRepo.balanceState,
            removedSuggestions,
            settingsStore.data.map { it.isPinEnabled },
        ) { balanceState, removedList, isPinEnabled ->
            val baseSuggestions = when {
                balanceState.totalLightningSats > 0uL -> { // With Lightning
                    listOfNotNull(
                        Suggestion.BACK_UP,
                        Suggestion.SECURE.takeIf { !isPinEnabled },
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
                        Suggestion.BACK_UP,
                        Suggestion.SPEND,
                        Suggestion.SECURE.takeIf { !isPinEnabled },
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
                        Suggestion.BACK_UP,
                        Suggestion.SECURE.takeIf { !isPinEnabled },
                        Suggestion.SUPPORT,
                        Suggestion.INVITE,
                        Suggestion.PROFILE,
                    )
                }
            }
            //TODO REMOVE PROFILE CARD IF THE USER ALREADY HAS one
            return@combine baseSuggestions.filterNot { it in removedList }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }
}
