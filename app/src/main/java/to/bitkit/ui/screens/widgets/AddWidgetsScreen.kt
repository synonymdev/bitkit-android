package to.bitkit.ui.screens.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.models.WidgetType
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun AddWidgetsScreen(
    onWidgetSelected: (WidgetType) -> Unit,
    onBackCLick: () -> Unit,
    fiatSymbol: String,
    showWidgets: Boolean = true,
    onEnableInSettingsClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.widgets__add),
            onBackClick = onBackCLick,
            actions = { DrawerNavIcon() },
        )

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow(
                title = stringResource(R.string.widgets__price__name),
                subtitle = stringResource(R.string.widgets__price__description),
                iconRes = R.drawable.widget_chart_line,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                enabled = showWidgets,
                onClick = { onWidgetSelected(WidgetType.PRICE) },
                modifier = Modifier.testTag("WidgetListItem-price"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__news__name),
                subtitle = stringResource(R.string.widgets__news__description),
                iconRes = R.drawable.widget_newspaper,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                enabled = showWidgets,
                onClick = { onWidgetSelected(WidgetType.NEWS) },
                modifier = Modifier.testTag("WidgetListItem-news"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__blocks__name),
                subtitle = stringResource(R.string.widgets__blocks__description),
                iconRes = R.drawable.widget_cube,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                enabled = showWidgets,
                onClick = { onWidgetSelected(WidgetType.BLOCK) },
                modifier = Modifier.testTag("WidgetListItem-blocks"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__facts__name),
                subtitle = stringResource(R.string.widgets__facts__description),
                iconRes = R.drawable.widget_lightbulb,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                enabled = showWidgets,
                onClick = { onWidgetSelected(WidgetType.FACTS) },
                modifier = Modifier.testTag("WidgetListItem-facts"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__weather__name),
                subtitle = stringResource(R.string.widgets__weather__description),
                iconRes = R.drawable.widget_cloud,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                enabled = showWidgets,
                onClick = { onWidgetSelected(WidgetType.WEATHER) },
                modifier = Modifier.testTag("WidgetListItem-weather"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__calculator__name),
                subtitle = stringResource(R.string.widgets__calculator__description).replace(
                    "{fiatSymbol}",
                    fiatSymbol
                ),
                iconRes = R.drawable.widget_math_operation,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                enabled = showWidgets,
                onClick = { onWidgetSelected(WidgetType.CALCULATOR) },
                modifier = Modifier.testTag("WidgetListItem-calculator"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.widgets__suggestions__name),
                subtitle = stringResource(R.string.widgets__suggestions__description),
                iconRes = R.drawable.widget_suggestions,
                iconSize = 48.dp,
                maxLinesSubtitle = 1,
                enabled = showWidgets,
                onClick = { onWidgetSelected(WidgetType.SUGGESTIONS) },
                modifier = Modifier.testTag("WidgetListItem-suggestions"),
            )
        }
        FillHeight()
        if (!showWidgets) {
            PrimaryButton(
                text = stringResource(R.string.widgets__enable_in_settings),
                onClick = onEnableInSettingsClick,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalSpacer(8.dp)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        AddWidgetsScreen(
            onWidgetSelected = {},
            fiatSymbol = "$",
            onBackCLick = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewDisabled() {
    AppThemeSurface {
        AddWidgetsScreen(
            onWidgetSelected = {},
            fiatSymbol = "$",
            onBackCLick = {},
            showWidgets = false,
        )
    }
}
