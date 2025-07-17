package to.bitkit.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.util.shareZipFile
import to.bitkit.viewmodels.DevSettingsViewModel

@Composable
fun DevSettingsScreen(
    navController: NavController,
    viewModel: DevSettingsViewModel = hiltViewModel(),
) {
    val app = appViewModel ?: return
    val activity = activityListViewModel ?: return
    val settings = settingsViewModel ?: return
    val context = LocalContext.current

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__dev_title),
            onBackClick = { navController.popBackStack() },
            actions = { CloseNavIcon(onClick = { navController.navigateToHome() }) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow("Logs") { navController.navigate(Routes.Logs) }
            SettingsButtonRow("Channel Orders") { navController.navigate(Routes.ChannelOrdersSettings) }

            if (Env.network == Network.REGTEST) {
                SectionHeader("REGTEST")

                SettingsButtonRow("Blocktank Regtest") { navController.navigate(Routes.RegtestSettings) }
                SettingsTextButtonRow("Generate Test Activities") { activity.generateRandomTestData() }
            }

            SectionHeader("APP CACHE")

            SettingsTextButtonRow(
                title = "Reset Settings Store",
                onClick = {
                    settings.reset()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Settings store reset")
                }
            )
            SettingsTextButtonRow(
                title = "Reset All Activities",
                onClick = {
                    activity.removeAllActivities()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Activities removed")
                }
            )
            SettingsTextButtonRow(
                title = "Reset Backup State",
                onClick = {
                    viewModel.resetBackupState()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Backup state reset")
                }
            )
            SettingsTextButtonRow(
                title = "Export Logs",
                onClick = {
                    viewModel.zipLogsForSharing { uri -> context.shareZipFile(uri) }
                }
            )
            SettingsTextButtonRow(
                title = "Reset Widgets State",
                onClick = {
                    viewModel.resetWidgetsState()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Widgets state reset")
                }
            )
            SettingsTextButtonRow(
                title = "Refresh Currency Rates",
                onClick = {
                    viewModel.refreshCurrencyRates()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Currency rates refreshed")
                }
            )

            SectionHeader("DEBUG")

            SettingsTextButtonRow("Fake New BG Transaction", onClick = viewModel::fakeBgTransaction)
            SettingsTextButtonRow("Open Channel To Trusted Peer", onClick = viewModel::openChannel)

            SectionHeader("NOTIFICATIONS")

            SettingsTextButtonRow("Register For LSP Notifications", onClick = viewModel::registerForNotifications)
            SettingsTextButtonRow("Test LSP Notification ", onClick = viewModel::testLspNotification)
        }
    }
}
