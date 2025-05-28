package to.bitkit.ui.settings.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.WidgetType
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun AddWidgetsScreen(
    onClose: () -> Unit,
    onWidgetSelected: (WidgetType) -> Unit,
    fiatSymbol: String,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.widgets__add),
            onBackClick = null,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier.padding(horizontal = 32.dp)
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.widgets__price__name),
                subtitle = stringResource(R.string.widgets__price__description),
                iconRes = R.drawable.widget_chart_line,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                onClick = { onWidgetSelected(WidgetType.PRICE) }
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__news__name),
                subtitle = stringResource(R.string.widgets__news__description),
                iconRes = R.drawable.widget_newspaper,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                onClick = { onWidgetSelected(WidgetType.NEWS) }
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__blocks__name),
                subtitle = stringResource(R.string.widgets__blocks__description),
                iconRes = R.drawable.widget_cube,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                onClick = { onWidgetSelected(WidgetType.BLOCK) }
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__facts__name),
                subtitle = stringResource(R.string.widgets__facts__description),
                iconRes = R.drawable.widget_lightbulb,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                onClick = { onWidgetSelected(WidgetType.FACTS) }
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__weather__name),
                subtitle = stringResource(R.string.widgets__weather__description),
                iconRes = R.drawable.widget_cloud,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                onClick = { onWidgetSelected(WidgetType.WEATHER) }
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__calculator__name),
                subtitle = stringResource(R.string.widgets__calculator__description).replace("{fiatSymbol}", fiatSymbol),
                iconRes = R.drawable.widget_math_operation,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                onClick = { onWidgetSelected(WidgetType.CALCULATOR) }
            )
        }
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AddWidgetsScreen(
            onClose = {},
            onWidgetSelected = {},
            fiatSymbol = "$"
        )
    }
}
