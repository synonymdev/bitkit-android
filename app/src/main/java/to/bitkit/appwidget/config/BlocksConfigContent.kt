package to.bitkit.appwidget.config

import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.widget.BlockModel
import to.bitkit.models.widget.BlocksPreferences
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.Colors

@Suppress("LongParameterList")
@Composable
internal fun BlocksConfigContent(
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
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int,
    label: String,
    value: String,
    isEnabled: Boolean,
    onToggle: () -> Unit,
) {
    Column(modifier = modifier) {
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
                    color = Colors.White,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(
                    painter = painterResource(R.drawable.ic_checkmark),
                    contentDescription = null,
                    tint = if (isEnabled) Colors.Brand else Colors.Gray3,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        HorizontalDivider()
    }
}
