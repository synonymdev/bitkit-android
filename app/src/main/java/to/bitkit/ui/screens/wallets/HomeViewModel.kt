package to.bitkit.ui.screens.wallets

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.data.AppStorage
import to.bitkit.data.SettingsStore
import to.bitkit.models.Suggestion
import to.bitkit.models.toSuggestionOrNull
import to.bitkit.models.widget.ArticleModel
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

    val suggestions: StateFlow<List<Suggestion>> = createSuggestionsFlow()
    private val articles: StateFlow<List<ArticleModel>> = createArticlesFlow()
    private val _currentArticle = MutableStateFlow(articles.value.firstOrNull())
    val currentArticle: StateFlow<ArticleModel?> = _currentArticle.asStateFlow()
    private val showWidgets = settingsStore.data.map { it.showWidgets }

    init {
        setupWidgets()
    }

    fun removeSuggestion(suggestion: Suggestion) {
        appStorage.addSuggestionToRemovedList(suggestion)
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

    private fun createArticlesFlow(): StateFlow<List<ArticleModel>> {
        val articles = widgetsRepo.articlesFlow.map { it.articles.map { article -> article.toArticleModel() } }
        return articles.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    }

    private fun setupWidgets() {
        viewModelScope.launch {
            showWidgets.collect { show ->
                if (show) {
                    widgetsRepo.updateArticles()
                    articles.first { it.isNotEmpty() }
                    getRandomArticle()
                } else {
                    _currentArticle.update { null }
                }
            }
        }
    }

    private fun getRandomArticle() {
        viewModelScope.launch {
            _currentArticle.update { articles.value.randomOrNull() }
            delay(30.seconds)
            if (showWidgets.first()) getRandomArticle()
        }
    }
}
