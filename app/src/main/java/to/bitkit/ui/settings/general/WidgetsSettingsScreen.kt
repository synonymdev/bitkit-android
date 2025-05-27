package to.bitkit.ui.settings.general

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun WidgetsSettingsScreen(
    navController: NavController,
) {
    val settings = settingsViewModel ?: return

    val showWidgets by settings.showWidgets.collectAsStateWithLifecycle()
    val showWidgetTitles by settings.showWidgetTitles.collectAsStateWithLifecycle()

    WidgetsSettingsContent(
        onBackClick = { navController.popBackStack() },
        onCloseClick = { navController.navigateToHome() },
        showWidgets = showWidgets,
        showWidgetTitles = showWidgetTitles,
        onShowWidgetsClick = { settings.setShowWidgets(!showWidgets) },
        onShowWidgetTitlesClick = { settings.setShowWidgetTitles(!showWidgetTitles) },
    )
}

@Composable
private fun WidgetsSettingsContent(
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    showWidgets: Boolean,
    onShowWidgetsClick: () -> Unit = {},
    showWidgetTitles: Boolean,
    onShowWidgetTitlesClick: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__widgets__nav_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings__widgets__showWidgets),
                isChecked = showWidgets,
                onClick = onShowWidgetsClick,
            )
            SettingsSwitchRow(
                title = stringResource(R.string.settings__widgets__showWidgetTitles),
                isChecked = showWidgetTitles,
                onClick = onShowWidgetTitlesClick,
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WidgetsSettingsContent(
            showWidgets = true,
            showWidgetTitles = false,
        )
    }
}
