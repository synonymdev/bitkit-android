package to.bitkit.ui.screens.wallets

import androidx.compose.runtime.Stable
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.FactsPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.screens.widgets.blocks.WeatherModel

@Stable
data class HomeUiState(
    val suggestions: List<Suggestion> = listOf(),
    val showWidgets: Boolean = false,
    val showWidgetTitles: Boolean = false,
    val widgetsWithPosition: List<WidgetWithPosition> = emptyList(),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences(),
    val currentArticle: ArticleModel? = null,
    val currentFact: String? = null,
    val factsPreferences: FactsPreferences = FactsPreferences(),
    val facts: List<String> = listOf(),
    val blocksPreferences: BlocksPreferences = BlocksPreferences(),
    val currentBlock: BlockModel? = null,
    val weatherPreferences: WeatherPreferences = WeatherPreferences(),
    val currentWeather: WeatherModel? = null,
    val pricePreferences: PricePreferences = PricePreferences(),
    val currentPrice: PriceDTO? = null,
    val isEditingWidgets: Boolean = false,
    val deleteWidgetAlert: WidgetType? = null,
    val highBalanceSheetVisible: Boolean = false,
    val showEmptyState: Boolean = false,
)
