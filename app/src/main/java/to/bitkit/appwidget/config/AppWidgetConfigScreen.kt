package to.bitkit.appwidget.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors

@Composable
fun AppWidgetConfigScreen(
    viewModel: AppWidgetConfigViewModel,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    when (state.type) {
        AppWidgetType.PRICE -> PriceConfigContent(
            state = state,
            onTogglePair = { viewModel.togglePricePair(it) },
            onSelectPeriod = { viewModel.selectPricePeriod(it) },
            onToggleSource = { viewModel.togglePriceSource() },
            onReset = { viewModel.resetPreferences() },
            onSave = { viewModel.saveAndFinish(onConfirm) },
            onCancel = onCancel,
        )
    }
}

@Composable
private fun PriceConfigContent(
    state: AppWidgetConfigUiState,
    onTogglePair: (TradingPair) -> Unit,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onToggleSource: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val prefs = state.pricePreferences
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.widgets__widget__edit),
            onBackClick = onCancel,
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(26.dp))

            BodyM(
                text = stringResource(R.string.widgets__widget__edit_description).replace(
                    "{name}",
                    stringResource(R.string.widgets__price__name),
                ),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            BodySSB(
                text = stringResource(R.string.appwidget__price__trading_pairs),
                color = Colors.White64,
            )
            Spacer(modifier = Modifier.height(8.dp))

            for (pair in TradingPair.entries) {
                ConfigToggleRow(
                    label = pair.displayName,
                    isEnabled = pair in prefs.enabledPairs,
                    onClick = { onTogglePair(pair) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            BodySSB(
                text = stringResource(R.string.appwidget__price__period),
                color = Colors.White64,
            )
            Spacer(modifier = Modifier.height(8.dp))

            for (period in GraphPeriod.entries) {
                ConfigToggleRow(
                    label = period.value,
                    isEnabled = period == prefs.period,
                    onClick = { onSelectPeriod(period) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            ConfigToggleRow(
                label = stringResource(R.string.widgets__widget__source),
                isEnabled = prefs.showSource,
                onClick = onToggleSource,
            )
        }

        Row(
            modifier = Modifier
                .padding(vertical = 21.dp, horizontal = 16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__reset),
                enabled = prefs != state.pricePreferences,
                fullWidth = false,
                onClick = onReset,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                fullWidth = false,
                onClick = onSave,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ConfigToggleRow(
    label: String,
    isEnabled: Boolean,
    onClick: () -> Unit,
) {
    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(vertical = 12.dp)
                .fillMaxWidth(),
        ) {
            BodySSB(
                text = label,
                color = Colors.White64,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = if (isEnabled) Colors.Brand else Colors.White50,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        HorizontalDivider()
    }
}
