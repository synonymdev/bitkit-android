package to.bitkit.ui.screens.wallets

import androidx.compose.runtime.Stable
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.HeadlinePreferences

@Stable
data class HomeUiState(
    val suggestions: List<Suggestion> = listOf(),
    val showWidgets: Boolean = false,
    val showWidgetTitles: Boolean = false,
    val widgetsWithPosition: List<WidgetWithPosition> = emptyList(),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences(),
    val currentArticle: ArticleModel? = null,
    val currentFact: String? = null,
    val facts: List<String> = listOf(),
)
