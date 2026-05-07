package to.bitkit.appwidget.config

import androidx.compose.foundation.background
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
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.widget.WeatherDataOption
import to.bitkit.models.widget.WeatherPreferences
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.widgets.weather.WeatherFeeValueText
import to.bitkit.ui.theme.Colors

@Composable
internal fun WeatherConfigContent(
    state: AppWidgetConfigUiState,
    onSelectOption: (WeatherDataOption) -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val prefs = state.weatherPreferences
    val weather = state.previewWeather

    ScreenColumn(
        noBackground = true,
        modifier = modifier.background(Colors.Gray7)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.widgets__weather__name),
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
                text = stringResource(R.string.widgets__widget__display),
                color = Colors.White64,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            WeatherOptionRow(
                label = stringResource(R.string.widgets__weather__current_fee),
                value = weather.currentFee,
                isSelected = prefs.selectedOption == WeatherDataOption.CURRENT_FEE_FIAT,
                onClick = { onSelectOption(WeatherDataOption.CURRENT_FEE_FIAT) },
            )
            HorizontalDivider()

            WeatherOptionRow(
                label = stringResource(R.string.widgets__weather__current_fee),
                value = weather.currentFeeSatsFormatted,
                isSelected = prefs.selectedOption == WeatherDataOption.CURRENT_FEE_SATS,
                onClick = { onSelectOption(WeatherDataOption.CURRENT_FEE_SATS) },
            )
            HorizontalDivider()

            WeatherOptionRow(
                label = stringResource(R.string.widgets__weather__next_block),
                value = weather.nextBlockFee,
                isSelected = prefs.selectedOption == WeatherDataOption.NEXT_BLOCK_INCLUSION,
                onClick = { onSelectOption(WeatherDataOption.NEXT_BLOCK_INCLUSION) },
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
                enabled = prefs != WeatherPreferences(),
                fullWidth = false,
                onClick = onReset,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = stringResource(R.string.common__save),
                isLoading = state.isSaving,
                enabled = !state.isSaving && prefs.selectedOption != null,
                fullWidth = false,
                onClick = onSave,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun WeatherOptionRow(
    label: String,
    value: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(vertical = 8.dp)
            .fillMaxWidth()
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            Caption13Up(
                text = label,
                color = Colors.White64,
            )
            WeatherFeeValueText(
                text = value,
                color = Colors.Green,
            )
        }
        IconButton(onClick = onClick) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = if (isSelected) Colors.Brand else Colors.White50,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
