package to.bitkit.ui.settings.widgets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.R
import to.bitkit.ui.components.settings.SettingsButtonRow

@Composable
fun AddWidgetsScreen(
    onClose: () -> Unit,
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
                onClick = {}
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
        )
    }
}
