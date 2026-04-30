package to.bitkit.appwidget.config

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.appwidget.model.AppWidgetType
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.models.widget.ArticleModel
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.models.widget.HeadlinePreferences
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.price.label
import to.bitkit.ui.theme.Colors

@Composable
fun AppWidgetConfigScreen(
    viewModel: AppWidgetConfigViewModel,
    onConfirm: suspend () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.type) {
        AppWidgetType.PRICE -> PriceConfigContent(
            state = state,
            onSelectPair = { viewModel.selectPricePair(it) },
            onSelectPeriod = { viewModel.selectPricePeriod(it) },
            onReset = { viewModel.resetPreferences() },
            onSave = { viewModel.saveAndFinish(onConfirm) },
            onCancel = onCancel,
        )

        AppWidgetType.HEADLINES -> HeadlinesConfigContent(
            state = state,
            onToggleSource = { viewModel.toggleShowSource() },
            onToggleTime = { viewModel.toggleShowTime() },
            onReset = { viewModel.resetPreferences() },
            onSave = { viewModel.saveAndFinish(onConfirm) },
            onCancel = onCancel,
        )

        AppWidgetType.BLOCKS -> BlocksConfigContent(
            state = state,
            onToggleBlock = { viewModel.toggleBlockShowBlock() },
            onToggleTime = { viewModel.toggleBlockShowTime() },
            onToggleDate = { viewModel.toggleBlockShowDate() },
            onToggleTransactions = { viewModel.toggleBlockShowTransactions() },
            onToggleSize = { viewModel.toggleBlockShowSize() },
            onToggleFees = { viewModel.toggleBlockShowFees() },
            onToggleSource = { viewModel.toggleBlockShowSource() },
            onReset = { viewModel.resetPreferences() },
            onSave = { viewModel.saveAndFinish(onConfirm) },
            onCancel = onCancel,
        )
    }
}

@Composable
private fun PriceConfigContent(
    state: AppWidgetConfigUiState,
    onSelectPair: (TradingPair) -> Unit,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val prefs = state.pricePreferences
    val selectedPair = prefs.enabledPairs.firstOrNull() ?: TradingPair.BTC_USD

    ScreenColumn(
        noBackground = true,
        modifier = Modifier.background(Colors.Gray7)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__price__name),
            onBackClick = onCancel,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)

            Caption13Up(
                text = stringResource(R.string.appwidget__price__currency),
                color = Colors.White64,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            for (pair in TradingPair.entries) {
                SelectableRow(
                    label = pair.displayName,
                    isSelected = pair == selectedPair,
                    onClick = { onSelectPair(pair) },
                )
            }

            VerticalSpacer(16.dp)
            Caption13Up(
                text = stringResource(R.string.appwidget__price__timeframe),
                color = Colors.White64,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            for (period in GraphPeriod.entries) {
                SelectableRow(
                    label = period.label(),
                    isSelected = period == prefs.period,
                    onClick = { onSelectPeriod(period) },
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                enabled = prefs != PricePreferences(),
                fullWidth = false,
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                isLoading = state.isSaving,
                enabled = !state.isSaving,
                fullWidth = false,
                onClick = onSave,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun HeadlinesConfigContent(
    state: AppWidgetConfigUiState,
    onToggleSource: () -> Unit,
    onToggleTime: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val prefs = state.headlinePreferences
    val previewArticle = ArticleModel(
        title = "How Bitcoin changed El Salvador in more ways",
        timeAgo = "21 minutes ago",
        publisher = "bitcoinmagazine.com",
        link = "",
    )

    ScreenColumn(
        noBackground = true,
        modifier = Modifier.background(Colors.Gray7)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__news__name),
            onBackClick = onCancel,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)

            Caption13Up(
                text = stringResource(R.string.widgets__widget__content),
                color = Colors.White64,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            ToggleRow(
                content = {
                    Title(
                        text = previewArticle.title,
                        modifier = Modifier.weight(1f)
                    )
                },
                isEnabled = true,
                onToggle = {},
                toggleEnabled = false,
            )
            HorizontalDivider()

            ToggleRow(
                content = {
                    BodySSB(
                        text = previewArticle.publisher,
                        color = Colors.Brand,
                        modifier = Modifier.weight(1f)
                    )
                },
                isEnabled = prefs.showSource,
                onToggle = onToggleSource,
            )
            HorizontalDivider()

            ToggleRow(
                content = {
                    BodySSB(
                        text = previewArticle.timeAgo,
                        color = Colors.White64,
                        modifier = Modifier.weight(1f)
                    )
                },
                isEnabled = prefs.showTime,
                onToggle = onToggleTime,
            )
            HorizontalDivider()
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                enabled = prefs != HeadlinePreferences(),
                fullWidth = false,
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                isLoading = state.isSaving,
                enabled = !state.isSaving,
                fullWidth = false,
                onClick = onSave,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Suppress("LongParameterList")
@Composable
private fun BlocksConfigContent(
    state: AppWidgetConfigUiState,
    onToggleBlock: () -> Unit,
    onToggleTime: () -> Unit,
    onToggleDate: () -> Unit,
    onToggleTransactions: () -> Unit,
    onToggleSize: () -> Unit,
    onToggleFees: () -> Unit,
    onToggleSource: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val prefs = state.blocksPreferences
    val previewBlock = remember {
        BlockModel(
            height = "761,405",
            time = "01:31:42 UTC",
            date = "11/2/2022",
            transactionCount = "2,175",
            size = "1,606 Kb",
            source = "mempool.io",
            fees = "25 059 357",
        )
    }

    ScreenColumn(
        noBackground = true,
        modifier = Modifier.background(Colors.Gray7)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__blocks__name),
            onBackClick = onCancel,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            VerticalSpacer(16.dp)

            Caption13Up(
                text = stringResource(R.string.widgets__widget__data),
                color = Colors.White64,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            BlockToggleRow(
                icon = R.drawable.ic_cube,
                label = stringResource(R.string.widgets__blocks__field__block),
                value = previewBlock.height,
                isEnabled = prefs.showBlock,
                onToggle = onToggleBlock,
            )
            BlockToggleRow(
                icon = R.drawable.ic_clock,
                label = stringResource(R.string.widgets__blocks__field__time),
                value = previewBlock.time,
                isEnabled = prefs.showTime,
                onToggle = onToggleTime,
            )
            BlockToggleRow(
                icon = R.drawable.ic_calendar,
                label = stringResource(R.string.widgets__blocks__field__date),
                value = previewBlock.date,
                isEnabled = prefs.showDate,
                onToggle = onToggleDate,
            )
            BlockToggleRow(
                icon = R.drawable.ic_transfer,
                label = stringResource(R.string.widgets__blocks__field__transactions),
                value = previewBlock.transactionCount,
                isEnabled = prefs.showTransactions,
                onToggle = onToggleTransactions,
            )
            BlockToggleRow(
                icon = R.drawable.ic_file_text,
                label = stringResource(R.string.widgets__blocks__field__size),
                value = previewBlock.size,
                isEnabled = prefs.showSize,
                onToggle = onToggleSize,
            )
            BlockToggleRow(
                icon = R.drawable.ic_coins,
                label = stringResource(R.string.widgets__blocks__field__fees),
                value = previewBlock.fees,
                isEnabled = prefs.showFees,
                onToggle = onToggleFees,
            )
            BlockToggleRow(
                icon = R.drawable.ic_globe,
                label = stringResource(R.string.widgets__widget__source),
                value = previewBlock.source,
                isEnabled = prefs.showSource,
                onToggle = onToggleSource,
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                enabled = prefs != BlocksPreferences(),
                fullWidth = false,
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                isLoading = state.isSaving,
                enabled = !state.isSaving && prefs.run {
                    showBlock || showTime || showDate || showTransactions || showSize || showFees || showSource
                },
                fullWidth = false,
                onClick = onSave,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BlockToggleRow(
    @DrawableRes icon: Int,
    label: String,
    value: String,
    isEnabled: Boolean,
    onToggle: () -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                tint = Colors.Brand,
                modifier = Modifier.size(20.dp)
            )
            BodyM(
                text = label,
                color = Colors.White80,
                modifier = Modifier.weight(1f)
            )
            if (value.isNotEmpty()) {
                BodySSB(
                    text = value,
                    color = Colors.White
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = if (isEnabled) Colors.Brand else Colors.White50,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun ToggleRow(
    content: @Composable RowScope.() -> Unit,
    isEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    toggleEnabled: Boolean = true,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
    ) {
        content()
        IconButton(
            onClick = onToggle,
            enabled = toggleEnabled,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = if (isEnabled) Colors.Brand else Colors.White50,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun SelectableRow(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp)
        ) {
            BodySSB(
                text = label,
                color = if (isSelected) Colors.White else Colors.White64,
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = Colors.Brand,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        HorizontalDivider()
    }
}
