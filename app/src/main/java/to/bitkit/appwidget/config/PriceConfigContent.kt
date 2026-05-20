package to.bitkit.appwidget.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.data.dto.price.GraphPeriod
import to.bitkit.data.dto.price.TradingPair
import to.bitkit.ext.label
import to.bitkit.models.widget.PricePreferences
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors

@Composable
internal fun PriceConfigContent(
    state: AppWidgetConfigUiState,
    onSelectPair: (TradingPair) -> Unit,
    onSelectPeriod: (GraphPeriod) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val prefs = state.pricePreferences
    val selectedPair = prefs.enabledPairs.firstOrNull() ?: TradingPair.BTC_USD

    ScreenColumn {
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
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = if (isSelected) Colors.Brand else Colors.Gray3,
                modifier = Modifier.size(32.dp)
            )
        }
        HorizontalDivider()
    }
}
