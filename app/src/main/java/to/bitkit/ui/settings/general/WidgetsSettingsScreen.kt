package to.bitkit.ui.settings.general

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsIcon
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
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
        showWidgets = showWidgets,
        showWidgetTitles = showWidgetTitles,
        onShowWidgetsClick = { settings.setShowWidgets(!showWidgets) },
        onShowWidgetTitlesClick = { settings.setShowWidgetTitles(!showWidgetTitles) },
        onResetWidgetsClick = { settings.resetWidgets() },
        onResetSuggestionsClick = { settings.resetDismissedSuggestions() },
    )
}

@Composable
private fun WidgetsSettingsContent(
    showWidgets: Boolean,
    showWidgetTitles: Boolean,
    onBackClick: () -> Unit = {},
    onShowWidgetsClick: () -> Unit = {},
    onShowWidgetTitlesClick: () -> Unit = {},
    onResetWidgetsClick: () -> Unit = {},
    onResetSuggestionsClick: () -> Unit = {},
) {
    var showResetWidgetsDialog by remember { mutableStateOf(false) }
    var showResetSuggestionsDialog by remember { mutableStateOf(false) }

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__widgets__nav_title),
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // Display section
            SectionHeader(title = stringResource(R.string.settings__widgets__section_display))

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

            // Reset section
            SectionHeader(
                title = stringResource(R.string.settings__widgets__section_reset),
                padding = androidx.compose.foundation.layout.PaddingValues(top = 16.dp),
            )

            SettingsButtonRow(
                title = stringResource(R.string.settings__widgets__reset_widgets),
                icon = { SettingsIcon(R.drawable.ic_arrow_counter_clockwise) },
                onClick = { showResetWidgetsDialog = true },
                modifier = Modifier.testTag("ResetWidgets"),
            )
            SettingsButtonRow(
                title = stringResource(R.string.settings__widgets__reset_suggestions),
                icon = { SettingsIcon(R.drawable.ic_arrow_counter_clockwise) },
                onClick = { showResetSuggestionsDialog = true },
                modifier = Modifier.testTag("ResetSuggestions"),
            )
        }

        if (showResetWidgetsDialog) {
            AppAlertDialog(
                title = stringResource(R.string.settings__widgets__reset_widgets_dialog_title),
                text = stringResource(R.string.settings__widgets__reset_widgets_dialog_description),
                confirmText = stringResource(R.string.settings__adv__reset_confirm),
                onConfirm = {
                    onResetWidgetsClick()
                    showResetWidgetsDialog = false
                },
                onDismiss = { showResetWidgetsDialog = false },
                modifier = Modifier.testTag("reset_widgets_dialog"),
            )
        }

        if (showResetSuggestionsDialog) {
            AppAlertDialog(
                title = stringResource(R.string.settings__adv__reset_title),
                text = stringResource(R.string.settings__adv__reset_desc),
                confirmText = stringResource(R.string.settings__adv__reset_confirm),
                onConfirm = {
                    onResetSuggestionsClick()
                    showResetSuggestionsDialog = false
                },
                onDismiss = { showResetSuggestionsDialog = false },
                modifier = Modifier.testTag("reset_suggestions_dialog"),
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
