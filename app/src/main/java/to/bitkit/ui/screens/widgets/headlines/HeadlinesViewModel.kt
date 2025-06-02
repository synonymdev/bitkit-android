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
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.toArticleModel
import to.bitkit.repositories.WidgetsRepo
import javax.inject.Inject

@HiltViewModel
class HeadlinesViewModel @Inject constructor(
    private val widgetsRepo: WidgetsRepo
) : ViewModel() {

    // MARK: - Public StateFlows

    val headlinePreferences: StateFlow<HeadlinePreferences> = widgetsRepo.widgetsDataFlow
        .map { it.headlinePreferences }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = HeadlinePreferences()
        )

    val isNewsWidgetEnabled: StateFlow<Boolean> = widgetsRepo.widgetsDataFlow
        .map { widgetsData ->
            widgetsData.widgets.any { it.type == WidgetType.NEWS }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = false
        )

    val showWidgetTitles: StateFlow<Boolean> = widgetsRepo.showWidgetTitles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
            initialValue = true
        )

    val currentArticle: StateFlow<ArticleModel> = widgetsRepo.articlesFlow.map { articles ->
        articles.map { it.toArticleModel() }.randomOrNull() ?: DEFAULT_ARTICLE
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT),
        initialValue = DEFAULT_ARTICLE
    )

    // MARK: - Custom Preferences (for settings UI)

    private val _customPreferences = MutableStateFlow(HeadlinePreferences())
    val customPreferences: StateFlow<HeadlinePreferences> = _customPreferences.asStateFlow()


    init {
        initializeCustomPreferences()
    }

    // MARK: - Public Methods

    fun toggleShowTime() {
        _customPreferences.update { preferences ->
            preferences.copy(showTime = !preferences.showTime)
        }
    }

    fun toggleShowSource() {
        _customPreferences.update { preferences ->
            preferences.copy(showSource = !preferences.showSource)
        }
    }

    fun resetCustomPreferences() {
        _customPreferences.value = HeadlinePreferences()
    }

    fun savePreferences() {
        viewModelScope.launch {
            widgetsRepo.updateHeadlinePreferences(_customPreferences.value)
            widgetsRepo.addWidget(WidgetType.NEWS)
        }
    }

    fun removeWidget() {
        viewModelScope.launch {
            widgetsRepo.deleteWidget(WidgetType.NEWS)
        }
    }

    // MARK: - Private Methods

    private fun initializeCustomPreferences() {
        viewModelScope.launch {
            headlinePreferences.collect { preferences ->
                _customPreferences.value = preferences
            }
        }
    }

    companion object {
        private const val SUBSCRIPTION_TIMEOUT = 5000L

        private val DEFAULT_ARTICLE = ArticleModel(
            timeAgo = "21 minutes ago",
            title = "How Bitcoin changed El Salvador in more ways",
            publisher = "bitcoinmagazine.com",
            link = "https://bitcoinmagazine.com"
        )
    }
}
