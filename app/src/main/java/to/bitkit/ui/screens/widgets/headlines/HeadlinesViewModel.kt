package to.bitkit.ui.screens.widgets.headlines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.toArticleModel
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class HeadlinesViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {

    val headlinePreferences: StateFlow<HeadlinePreferences> = widgetsRepo.widgetsDataFlow
        .map { it.headlinePreferences }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HeadlinePreferences())

    private val articles: StateFlow<List<ArticleModel>> = widgetsRepo.articlesFlow
        .map { articles -> articles.map { it.toArticleModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val showWidgetTitles = widgetsRepo.showWidgets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    private val _currentArticle = MutableStateFlow(
        ArticleModel(
            timeAgo = "21 minutes ago",
            title = "How Bitcoin changed El Salvador in more ways",
            publisher = "bitcoinmagazine.com",
            link = "bitcoinmagazine.com",
        )
    )
    val currentArticle: StateFlow<ArticleModel> = _currentArticle.asStateFlow()

    fun updateHeadlinesPreferences(preferences: HeadlinePreferences) {
        viewModelScope.launch { widgetsRepo.updateHeadlinePreferences(preferences) }
        //TODO ADD HEADLINES TO LIST IF NOT YET
    }

    init {
        getArticle()
    }

    private fun getArticle() {
        viewModelScope.launch {
            articles.collect { articles ->
                if (articles.isNotEmpty()) {
                    _currentArticle.update { articles.random() }
                }
            }

        }
    }
}
