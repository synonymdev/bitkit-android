package to.bitkit.ui.screens.widgets.price

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
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
import to.bitkit.ui.components.Headline
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Text13Up
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsButtonValue
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun PricePreviewScreen(
    priceViewModel: PriceViewModel,
    onClose: () -> Unit,
    onBack: () -> Unit,
    navigateEditWidget: () -> Unit,
) {
    val showWidgetTitles by priceViewModel.showWidgetTitles.collectAsStateWithLifecycle()
    val customPricePreferences by priceViewModel.customPreferences.collectAsStateWithLifecycle()
    val price by priceViewModel.currentPrice.collectAsStateWithLifecycle()
    val previewPrice by priceViewModel.previewPrice.collectAsStateWithLifecycle()
    val isPriceWidgetEnabled by priceViewModel.isPriceWidgetEnabled.collectAsStateWithLifecycle()
    val isLoading by priceViewModel.isLoading.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        priceViewModel.priceEffect.collect { effect ->
            when(effect) {
                PriceEffect.NavigateHome -> onClose()
            }
        }
    }

    PricePreviewContent(
        onClose = onClose,
        onBack = onBack,
        isPriceWidgetEnabled = isPriceWidgetEnabled,
        pricePreferences = customPricePreferences,
        showWidgetTitles = showWidgetTitles,
        priceDTO = previewPrice ?: price,
        onClickEdit = navigateEditWidget,
        onClickDelete = {
            priceViewModel.removeWidget()
            onClose()
        },
        onClickSave = {
            priceViewModel.savePreferences()
        },
        isLoading = isLoading
    )
}

@Composable
fun PricePreviewContent(
    onClose: () -> Unit,
    onBack: () -> Unit,
    onClickEdit: () -> Unit,
    onClickDelete: () -> Unit,
    onClickSave: () -> Unit,
    showWidgetTitles: Boolean,
    isPriceWidgetEnabled: Boolean,
    pricePreferences: PricePreferences,
    priceDTO: PriceDTO?,
    isLoading: Boolean
) {
    ScreenColumn(
        modifier = Modifier.testTag("price_preview_screen")
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__nav_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .testTag("main_content")
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("header_row"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Headline(
                    text = AnnotatedString(stringResource(R.string.widgets__price__name)),
                    modifier = Modifier
                        .width(180.dp)
                        .testTag("widget_title")
                )
                Icon(
                    painter = painterResource(R.drawable.widget_chart_line),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier
                        .size(64.dp)
                        .testTag("widget_icon")
                )
            }

            BodyM(
                text = stringResource(R.string.widgets__price__description),
                color = Colors.White64,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("widget_description")
            )

            HorizontalDivider(
                modifier = Modifier.testTag("divider")
            )

            SettingsButtonRow(
                title = stringResource(R.string.widgets__widget__edit),
                value = SettingsButtonValue.StringValue(
                    if (pricePreferences == PricePreferences()) {
                        stringResource(R.string.widgets__widget__edit_default)
                    } else {
                        stringResource(R.string.widgets__widget__edit_custom)
                    }
                ),
                onClick = onClickEdit,
                modifier = Modifier.testTag("edit_settings_button")
            )

            Spacer(modifier = Modifier.weight(1f))

            Text13Up(
                stringResource(R.string.common__preview),
                color = Colors.White64,
                modifier = Modifier
                    .padding(vertical = 16.dp)
                    .testTag("preview_label")
            )

            priceDTO?.let { dto ->
                PriceCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("price_card"),
                    showWidgetTitle = showWidgetTitles,
                    pricePreferences = pricePreferences,
                    priceDTO = dto
                )
            }
        }

        Row(
            modifier = Modifier
                .padding(vertical = 21.dp, horizontal = 16.dp)
                .fillMaxWidth()
                .testTag("buttons_row"),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isPriceWidgetEnabled) {
                SecondaryButton(
                    text = stringResource(R.string.common__delete),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("delete_button"),
                    fullWidth = false,
                    onClick = onClickDelete
                )
            }

            PrimaryButton(
                text = stringResource(R.string.common__save),
                modifier = Modifier
                    .weight(1f)
                    .testTag("save_button"),
                fullWidth = false,
                isLoading = isLoading,
                onClick = onClickSave
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        PricePreviewContent(
            onClose = {},
            onBack = {},
            showWidgetTitles = true,
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            pricePreferences = PricePreferences(),
            priceDTO = PriceDTO(
                widgets = listOf(
                    PriceWidgetData(
                        pair = TradingPair.BTC_USD,
                        change = Change(
                            isPositive = true,
                            formatted = "$ 20,326"
                        ),
                        price = "$20,326",
                        pastValues = listOf(1.0, 2.0, 3.0, 4.0),
                        period = GraphPeriod.ONE_DAY,
                    ),
                    PriceWidgetData(
                        pair = TradingPair.BTC_EUR,
                        change = Change(
                            isPositive = false,
                            formatted = "€ 20,326"
                        ),
                        price = "€ 20,326",
                        pastValues = listOf(1.0, 2.0, 3.0, 4.0),
                        period = GraphPeriod.ONE_DAY,
                    )
                )
            ),
            isPriceWidgetEnabled = false,
            isLoading = false
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview2() {
    AppThemeSurface {
        PricePreviewContent(
            onClose = {},
            onBack = {},
            showWidgetTitles = false,
            onClickEdit = {},
            onClickDelete = {},
            onClickSave = {},
            pricePreferences = PricePreferences(
                enabledPairs = listOf(TradingPair.BTC_USD, TradingPair.BTC_EUR),
                period = GraphPeriod.ONE_WEEK,
                showSource = true
            ),
            priceDTO = PriceDTO(
                widgets = listOf(
                    PriceWidgetData(
                        pair = TradingPair.BTC_USD,
                        change = Change(
                            isPositive = true,
                            formatted = "$ 20,326"
                        ),
                        price = "$20,326",
                        pastValues = listOf(1.0, 2.0, 3.0, 4.0),
                        period = GraphPeriod.ONE_DAY,
                    ),
                    PriceWidgetData(
                        pair = TradingPair.BTC_EUR,
                        change = Change(
                            isPositive = false,
                            formatted = "€ 20,326"
                        ),
                        price = "€ 20,326",
                        pastValues = listOf(1.0, 2.0, 3.0, 4.0),
                        period = GraphPeriod.ONE_DAY,
                    )
                )
            ),
            isPriceWidgetEnabled = true,
            isLoading = false
        )
    }
}
