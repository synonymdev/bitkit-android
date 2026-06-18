package to.bitkit.ui.screens.wallets

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.models.BannerItem
import to.bitkit.models.HwWallet
import to.bitkit.models.Suggestion
import to.bitkit.models.WidgetType
import to.bitkit.models.WidgetWithPosition
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.screens.widgets.blocks.WeatherModel

@Stable
data class HomeUiState(
    val suggestions: ImmutableList<Suggestion> = persistentListOf(),
    val hardwareWallets: ImmutableList<HwWallet> = persistentListOf(),
    val banners: ImmutableList<BannerItem> = persistentListOf(),
    val showWidgets: Boolean = false,
    val widgetsWithPosition: ImmutableList<WidgetWithPosition> = persistentListOf(),
    val headlinePreferences: HeadlinePreferences = HeadlinePreferences(),
    val currentArticle: ArticleModel? = null,
    val currentFact: String? = null,
    val facts: ImmutableList<String> = persistentListOf(),
    val blocksPreferences: BlocksPreferences = BlocksPreferences(),
    val currentBlock: BlockModel? = null,
    val weatherPreferences: WeatherPreferences = WeatherPreferences(),
    val currentWeather: WeatherModel? = null,
    val pricePreferences: PricePreferences = PricePreferences(),
    val currentPrice: PriceDTO? = null,
    val isEditingWidgets: Boolean = false,
    val deleteWidgetAlert: WidgetType? = null,
    val showEmptyState: Boolean = false,
    val currentPage: Int = 0,
    val showWidgetsOnboardingHint: Boolean = false,
)
