package to.bitkit.ui.screens.widgets.price

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.dto.price.Change
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.PriceDTO
import to.bitkit.data.dto.price.PriceWidgetData
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

private const val PAGE_SMALL = 0
private const val PAGE_WIDE = 1
private const val PAGE_COUNT = 2

@Composable
fun PricePreviewScreen(
    priceViewModel: PriceViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
) {
    val customPricePreferences by priceViewModel.customPreferences.collectAsStateWithLifecycle()
    val price by priceViewModel.currentPrice.collectAsStateWithLifecycle()
    val previewPrice by priceViewModel.previewPrice.collectAsStateWithLifecycle()
    val isPriceWidgetEnabled by priceViewModel.isPriceWidgetEnabled.collectAsStateWithLifecycle()
    val isLoading by priceViewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        priceViewModel.refreshOnDisplay()
    }

    LaunchedEffect(Unit) {
        priceViewModel.priceEffect.collect { effect ->
            when (effect) {
                PriceEffect.NavigateHome -> onClose()
            }
        }
    }

    PricePreviewContent(
        onBack = onBack,
        isPriceWidgetEnabled = isPriceWidgetEnabled,
        pricePreferences = customPricePreferences,
        priceDTO = previewPrice ?: price,
        onClickEdit = navigateEditWidget,
        onClickDelete = {
            priceViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            priceViewModel.savePreferences()
        },
        isLoading = isLoading,
    )
}

@Composable
fun PricePreviewContent(
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    isPriceWidgetEnabled: Boolean,
    pricePreferences: PricePreferences,
    priceDTO: PriceDTO?,
    isLoading: Boolean,
) {
    ScreenColumn(
        modifier = Modifier.testTag("price_preview_screen"),
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__price__name),
            onBackClick = onBack,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f),
        ) {
            VerticalSpacer(16.dp)

            BodyM(
                text = stringResource(R.string.widgets__price__description),
                color = Colors.White64,
                modifier = Modifier.testTag("widget_description"),
            )

            VerticalSpacer(16.dp)

            HorizontalDivider(
                modifier = Modifier.testTag("divider"),
            )

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__edit),
                value = SettingsButtonValue.StringValue(
                    if (pricePreferences == PricePreferences()) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    },
                ),
                onClick = onClickEdit,
                modifier = Modifier.testTag("WidgetEdit"),
            )

            if (priceDTO != null) {
                WidgetCarousel(
                    pricePreferences = pricePreferences,
                    priceDTO = priceDTO,
                    modifier = Modifier
                        .fillMaxWidth(),
                )
            } else {
                Box(modifier = Modifier.weight(1f))
            }
        }

        Row(
            modifier = Modifier
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                    top = 22.dp,
                )
                .fillMaxWidth()
                .testTag("buttons_row"),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isPriceWidgetEnabled) {
                SecondaryButton(
                    text = stringResource(R.string.common__delete),
                    fullWidth = false,
                    onClick = onClickDelete,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("WidgetDelete"),
                )
            }

            PrimaryButton(
                text = stringResource(R.string.common__save),
                fullWidth = false,
                isLoading = isLoading,
                onClick = onClickSave,
                modifier = Modifier
                    .weight(1f)
                    .testTag("WidgetSave"),
            )
        }
    }
}

@Composable
private fun WidgetCarousel(
    pricePreferences: PricePreferences,
    priceDTO: PriceDTO,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { PAGE_COUNT })

    Column(
        modifier = modifier.testTag("price_preview_carousel"),
        verticalArrangement = Arrangement.Center,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("price_preview_pager"),
        ) { page ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                when (page) {
                    PAGE_SMALL -> PriceCardSmall(
                        modifier = Modifier
                            .width(163.dp)
                            .height(192.dp)
                            .testTag("price_card_small"),
                        pricePreferences = pricePreferences,
                        priceDTO = priceDTO,
                    )

                    PAGE_WIDE -> PriceCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("price_card_wide"),
                        showWidgetTitle = false,
                        pricePreferences = pricePreferences,
                        priceDTO = priceDTO,
                    )
                }
            }
        }

        VerticalSpacer(16.dp)

        Caption13Up(
            text = stringResource(
                if (pagerState.currentPage == PAGE_SMALL) {
                    R.string.widgets__widget__size_small
                } else {
                    R.string.widgets__widget__size_wide
                },
            ),
            color = Colors.White64,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("widget_size_label"),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        VerticalSpacer(16.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("page_indicator"),
            horizontalArrangement = Arrangement.Center,
        ) {
            repeat(PAGE_COUNT) { index ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(8.dp)
                        .background(
                            color = if (pagerState.currentPage == index) Colors.White else Colors.White32,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        PricePreviewContent(
            onBack = {},
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            pricePreferences = PricePreferences(),
            priceDTO = SAMPLE_PRICE_DTO,
            isPriceWidgetEnabled = false,
            isLoading = false,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewWithDelete() {
    AppThemeSurface {
        PricePreviewContent(
            onBack = {},
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            pricePreferences = PricePreferences(
                enabledPairs = listOf(TradingPair.BTC_USD),
                period = GraphPeriod.ONE_WEEK,
            ),
            priceDTO = SAMPLE_PRICE_DTO,
            isPriceWidgetEnabled = true,
            isLoading = false,
        )
    }
}

private val SAMPLE_PRICE_DTO = PriceDTO(
    source = "Bitfinex.com",
    widgets = listOf(
        PriceWidgetData(
            pair = TradingPair.BTC_USD,
            change = Change(isPositive = true, formatted = "+1.24%"),
            price = "75,326",
            pastValues = listOf(1.0, 2.0, 1.5, 3.0, 2.5, 4.0),
            period = GraphPeriod.ONE_DAY,
        ),
    ),
)
