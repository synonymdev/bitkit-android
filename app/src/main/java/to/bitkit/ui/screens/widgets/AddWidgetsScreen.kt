package to.bitkit.ui.screens.widgets

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import to.bitkit.R
import to.bitkit.data.dto.FeeCondition
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.BitcoinDisplayUnit
import to.bitkit.models.Suggestion
import to.bitkit.models.USD_SYMBOL
import to.bitkit.models.WidgetType
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.CalculatorValues
import to.bitkit.models.widget.PricePreferences
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.screens.widgets.blocks.BlockCard
import to.bitkit.ui.screens.widgets.blocks.WeatherModel
import to.bitkit.ui.screens.widgets.calculator.components.CalculatorCardSmall
import to.bitkit.ui.screens.widgets.components.WidgetCardDimens
import to.bitkit.ui.screens.widgets.components.WidgetSheetTitle
import to.bitkit.ui.screens.widgets.components.widgetSheetContent
import to.bitkit.ui.screens.widgets.facts.FactsCardSmall
import to.bitkit.ui.screens.widgets.headlines.HeadlineCard
import to.bitkit.ui.screens.widgets.price.PriceCardSmall
import to.bitkit.ui.screens.widgets.suggestions.SuggestionsPreviewGrid
import to.bitkit.ui.screens.widgets.weather.WeatherCardSmall
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Insets

@Composable
fun AddWidgetsSheetContent(
    showWidgets: Boolean,
    modifier: Modifier = Modifier,
    fiatSymbol: String = USD_SYMBOL,
    onWidgetSelected: (WidgetType) -> Unit = {},
    onEnableInSettingsClick: () -> Unit = {},
    galleryScrollState: ScrollState = rememberScrollState(),
    weatherModel: WeatherModel? = null,
    article: ArticleModel? = null,
    block: BlockModel? = null,
    fact: String? = null,
    price: PriceDTO? = PreviewPrice,
    calculatorValues: CalculatorValues = CalculatorValues(),
) {
    Column(
        modifier = modifier
            .widgetSheetContent()
            .testTag("widgets_gallery_screen")
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(galleryScrollState)
                .testTag("widgets_gallery_scroll")
        ) {
            WidgetSheetTitle(
                title = stringResource(R.string.widgets__add),
                modifier = Modifier.testTag("widgets_gallery_title")
            )

            WidgetsGalleryList(
                fiatSymbol = fiatSymbol,
                showWidgets = showWidgets,
                onWidgetSelected = onWidgetSelected,
                weatherModel = weatherModel,
                article = article,
                block = block,
                fact = fact,
                price = price,
                calculatorValues = calculatorValues,
                modifier = Modifier.fillMaxWidth()
            )
        }

        EnableWidgetsButton(
            showWidgets = showWidgets,
            onEnableInSettingsClick = onEnableInSettingsClick,
        )
    }
}

@Composable
private fun WidgetsGalleryList(
    fiatSymbol: String,
    showWidgets: Boolean,
    onWidgetSelected: (WidgetType) -> Unit,
    modifier: Modifier = Modifier,
    weatherModel: WeatherModel? = null,
    article: ArticleModel? = null,
    block: BlockModel? = null,
    fact: String? = null,
    price: PriceDTO? = PreviewPrice,
    calculatorValues: CalculatorValues = CalculatorValues(),
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .padding(horizontal = 16.dp)
            .padding(top = 16.dp)
            .padding(bottom = Insets.Bottom + 24.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WidgetPreviewItem(
                title = stringResource(R.string.widgets__price__name),
                showWidgets = showWidgets,
                onClick = { onWidgetSelected(WidgetType.PRICE) },
                testTag = "WidgetListItem-price",
                modifier = Modifier.weight(1f)
            ) {
                if (price == null) {
                    PriceLoadingCard(modifier = Modifier.smallPreviewCard())
                } else {
                    PriceCardSmall(
                        pricePreferences = PreviewPricePreferences,
                        priceDTO = price,
                        backgroundColor = Colors.Gray6,
                        modifier = Modifier.smallPreviewCard()
                    )
                }
            }

            WidgetPreviewItem(
                title = stringResource(R.string.widgets__weather__name),
                showWidgets = showWidgets,
                onClick = { onWidgetSelected(WidgetType.WEATHER) },
                testTag = "WidgetListItem-weather",
                modifier = Modifier.weight(1f)
            ) {
                WeatherCardSmall(
                    weatherModel = weatherModel ?: PreviewWeather,
                    preferences = PreviewWeatherPreferences,
                    modifier = Modifier.smallPreviewCard()
                )
            }
        }

        WidgetPreviewItem(
            title = stringResource(R.string.widgets__news__name),
            showWidgets = showWidgets,
            onClick = { onWidgetSelected(WidgetType.NEWS) },
            testTag = "WidgetListItem-news",
            modifier = Modifier.fillMaxWidth()
        ) {
            val previewArticle = article ?: PreviewArticle
            HeadlineCard(
                time = previewArticle.timeAgo,
                headline = previewArticle.title,
                source = previewArticle.publisher,
                link = previewArticle.link,
                enabled = showWidgets,
                backgroundColor = Colors.Gray6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("headline_card_wide")
            )
        }

        WidgetPreviewItem(
            title = stringResource(R.string.widgets__blocks__name),
            showWidgets = showWidgets,
            onClick = { onWidgetSelected(WidgetType.BLOCK) },
            testTag = "WidgetListItem-blocks",
            modifier = Modifier.fillMaxWidth()
        ) {
            BlockCard(
                preferences = PreviewBlocksPreferences,
                block = block ?: PreviewBlock,
                backgroundColor = Colors.Gray6,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WidgetPreviewItem(
                title = stringResource(R.string.widgets__facts__name),
                showWidgets = showWidgets,
                onClick = { onWidgetSelected(WidgetType.FACTS) },
                testTag = "WidgetListItem-facts",
                testTagPlacement = WidgetPreviewTestTagPlacement.Title,
                layoutTestTag = "WidgetListItem-facts-layout",
                modifier = Modifier.weight(1f)
            ) {
                FactsCardSmall(
                    headline = fact ?: PREVIEW_FACT,
                    backgroundColor = Colors.Gray6,
                    modifier = Modifier.smallPreviewCard()
                )
            }

            WidgetPreviewItem(
                title = stringResource(R.string.widgets__calculator__name),
                showWidgets = showWidgets,
                onClick = { onWidgetSelected(WidgetType.CALCULATOR) },
                testTag = "WidgetListItem-calculator",
                testTagPlacement = WidgetPreviewTestTagPlacement.Title,
                layoutTestTag = "WidgetListItem-calculator-layout",
                modifier = Modifier.weight(1f)
            ) {
                CalculatorCardSmall(
                    btcPrimaryDisplayUnit = calculatorValues.displayUnit ?: BitcoinDisplayUnit.MODERN,
                    btcValue = calculatorValues.btcValue,
                    fiatSymbol = fiatSymbol,
                    fiatValue = calculatorValues.fiatValue,
                    modifier = Modifier.smallPreviewCard()
                )
            }
        }

        WidgetPreviewItem(
            title = stringResource(R.string.widgets__suggestions__name),
            showWidgets = showWidgets,
            onClick = { onWidgetSelected(WidgetType.SUGGESTIONS) },
            testTag = "WidgetListItem-suggestions",
            modifier = Modifier.fillMaxWidth()
        ) {
            SuggestionsPreviewGrid(
                suggestions = PREVIEW_SUGGESTIONS,
                onSuggestionClick = {
                    if (showWidgets) {
                        onWidgetSelected(WidgetType.SUGGESTIONS)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PriceLoadingCard(
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(Colors.Gray6)
    ) {
        CircularProgressIndicator(color = Colors.White64)
    }
}

@Composable
private fun WidgetPreviewItem(
    title: String,
    showWidgets: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String? = null,
    testTagPlacement: WidgetPreviewTestTagPlacement = WidgetPreviewTestTagPlacement.Item,
    layoutTestTag: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .optionalTestTag(layoutTestTag)
            .widgetPreviewTestTag(
                tag = testTag,
                tagPlacement = testTagPlacement,
                targetPlacement = WidgetPreviewTestTagPlacement.Item,
            )
            .alpha(if (showWidgets) 1f else DISABLED_CARD_ALPHA)
            .then(
                if (showWidgets) {
                    Modifier.semantics {
                        role = Role.Button
                        onClick {
                            onClick()
                            true
                        }
                    }
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WidgetPreviewTitle(
                title = title,
                testTag = testTag,
                testTagPlacement = testTagPlacement,
                showWidgets = showWidgets,
                onClick = onClick,
            )
            content()
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .clickableAlpha(
                    pressedAlpha = 1f,
                    enabled = showWidgets,
                    onClick = onClick,
                )
        )
    }
}

@Composable
private fun WidgetPreviewTitle(
    title: String,
    testTag: String?,
    testTagPlacement: WidgetPreviewTestTagPlacement,
    showWidgets: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (testTag == null || testTagPlacement != WidgetPreviewTestTagPlacement.Title) {
        BodyS(
            text = title,
            color = Colors.White64,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }

    val titleModifier = if (showWidgets) {
        modifier
            .fillMaxWidth()
            .testTag(testTag)
            .clickableAlpha(
                pressedAlpha = 1f,
                onClick = onClick,
            )
    } else {
        modifier
            .fillMaxWidth()
            .testTag(testTag)
    }

    Box(modifier = titleModifier) {
        BodyS(
            text = title,
            color = Colors.White64,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private enum class WidgetPreviewTestTagPlacement {
    Item,
    Title,
}

private fun Modifier.optionalTestTag(tag: String?): Modifier {
    if (tag == null) return this

    return testTag(tag)
}

private fun Modifier.widgetPreviewTestTag(
    tag: String?,
    tagPlacement: WidgetPreviewTestTagPlacement,
    targetPlacement: WidgetPreviewTestTagPlacement,
): Modifier {
    if (tag == null || tagPlacement != targetPlacement) return this

    return testTag(tag)
}

@Composable
private fun EnableWidgetsButton(
    showWidgets: Boolean,
    onEnableInSettingsClick: () -> Unit,
) {
    if (showWidgets) return

    PrimaryButton(
        text = stringResource(R.string.widgets__enable_in_settings),
        onClick = onEnableInSettingsClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = Insets.Bottom + 16.dp,
            )
            .testTag("WidgetEnableInSettings")
    )
}

private fun Modifier.smallPreviewCard(): Modifier =
    fillMaxWidth().height(WidgetCardDimens.COMPACT_CARD_SIZE.height)

private val PreviewPricePreferences = PricePreferences(
    enabledPairs = listOf(TradingPair.BTC_USD),
    period = GraphPeriod.ONE_DAY,
)

private val PreviewPrice = PriceDTO(
    widgets = listOf(
        PriceWidgetData(
            pair = TradingPair.BTC_USD,
            period = GraphPeriod.ONE_DAY,
            change = Change(isPositive = true, formatted = "+1.24%"),
            price = "75,326",
            pastValues = listOf(
                1.0,
                2.3,
                1.4,
                1.8,
                4.9,
                2.7,
                3.2,
                2.5,
                6.3,
                5.8,
                3.9,
                7.0,
            ),
        ),
    ),
)

private val PreviewWeather = WeatherModel(
    condition = FeeCondition.GOOD,
    title = R.string.widgets__weather__condition__good__title,
    shortTitle = R.string.widgets__weather__condition__good__short_title,
    description = R.string.widgets__weather__condition__good__description,
    currentFee = "$ 0.52",
    currentFeeSats = 52,
    currentFeeSatsFormatted = "52 sats/vByte",
    nextBlockFee = "2 sats/vByte",
    icon = FeeCondition.GOOD.icon,
)

private val PreviewWeatherPreferences = WeatherPreferences(
    selectedOption = WeatherDataOption.CURRENT_FEE_FIAT,
)

private val PreviewBlock = BlockModel(
    height = "761,405",
    time = "01:31:42 UTC",
    date = "11/2/2022",
    transactionCount = "2,175",
    size = "1,606kb",
    fees = "25 059 357",
)

private val PreviewBlocksPreferences = BlocksPreferences(
    showBlock = true,
    showTime = true,
    showDate = true,
    showTransactions = true,
    showSize = false,
    showFees = false,
)

private const val PREVIEW_FACT = "Bitcoin doesn’t need your personal information"
private val PreviewArticle = ArticleModel(
    timeAgo = "21 min ago",
    title = "How Bitcoin Changed El Salvador In More Ways...",
    publisher = "bitcoinmagazine.com",
    link = "https://bitcoinmagazine.com",
)
private val PREVIEW_SUGGESTIONS = persistentListOf(
    Suggestion.BACK_UP,
    Suggestion.SECURE,
    Suggestion.LIGHTNING,
    Suggestion.SUPPORT,
)
private const val DISABLED_CARD_ALPHA = 0.42f

@Preview(showSystemUi = true)
@Composable
private fun PreviewSheet() {
    AppThemeSurface {
        BottomSheetPreview {
            Column(
                modifier = Modifier.sheetHeight(),
            ) {
                AddWidgetsSheetContent(
                    showWidgets = true,
                )
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewDisabled() {
    AppThemeSurface {
        BottomSheetPreview {
            Column(
                modifier = Modifier.sheetHeight(),
            ) {
                AddWidgetsSheetContent(
                    showWidgets = false,
                )
            }
        }
    }
}
