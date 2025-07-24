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
            SettingsButtonRow("Fee Settings") { navController.navigate(Routes.FeeSettings) }
            SettingsButtonRow("Channel Orders") { navController.navigate(Routes.ChannelOrdersSettings) }

            SectionHeader("LOGS")
            SettingsButtonRow("Logs") { navController.navigate(Routes.Logs) }
            SettingsTextButtonRow(
                title = "Export Logs",
                onClick = {
                    viewModel.zipLogsForSharing { uri -> context.shareZipFile(uri) }
                }
            )

            if (Env.network == Network.REGTEST) {
                SectionHeader("REGTEST")

                SettingsButtonRow("Blocktank Regtest") { navController.navigate(Routes.RegtestSettings) }
            }

            SectionHeader("APP CACHE")

            SettingsTextButtonRow(
                title = "Reset Settings State",
                onClick = {
                    settings.reset()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Settings state reset")
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
            SettingsTextButtonRow(
                title = "Reset Metadata State",
                onClick = {
                    viewModel.resetMetadataState()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Metadata state reset")
                }
            )
            SettingsTextButtonRow(
                title = "Reset Blocktank State",
                onClick = {
                    viewModel.resetBlocktankState()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Blocktank state reset")
                }
            )
            SettingsTextButtonRow(
                title = "Reset Cache Store",
                onClick = {
                    viewModel.resetCacheStore()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Cache store reset")
                }
            )
            SettingsTextButtonRow(
                title = "Wipe App",
                onClick = {
                    viewModel.wipeWallet()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Wallet wiped")
                }
            )

            SectionHeader("DEBUG")

            SettingsTextButtonRow(
                title = "Generate Test Activities",
                onClick = {
                    val count = 100
                    activity.generateRandomTestData(count)
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Generated $count test activities")
                }
            )
            SettingsTextButtonRow(
                "Fake New BG Transaction",
                onClick = {
                    viewModel.fakeBgTransaction()
                    app.toast(type = Toast.ToastType.INFO, title = "Restart to see the transaction sheet")
                }
            )
            SettingsTextButtonRow(
                title = "Open Channel To Trusted Peer",
                onClick = {
                    viewModel.openChannel()
                }
            )

            SectionHeader("NOTIFICATIONS")

            SettingsTextButtonRow(
                title = "Register For LSP Notifications",
                onClick = {
                    viewModel.registerForNotifications()
                }
            )
            SettingsTextButtonRow(
                title = "Test LSP Notification",
                onClick = {
                    viewModel.testLspNotification()
                }
            )
        }
    }
}
