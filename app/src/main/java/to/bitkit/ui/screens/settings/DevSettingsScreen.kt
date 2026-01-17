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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.ToastText
import to.bitkit.ui.Routes
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.util.shareZipFile
import to.bitkit.ui.toaster
import to.bitkit.viewmodels.DevSettingsViewModel

@Composable
fun DevSettingsScreen(
    navController: NavController,
    viewModel: DevSettingsViewModel = hiltViewModel(),
) {
    val toaster = toaster
    val activity = activityListViewModel ?: return
    val settings = settingsViewModel ?: return
    val context = LocalContext.current

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.settings__dev_title),
            onBackClick = { navController.popBackStack() },
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SettingsButtonRow("Fee Settings") { navController.navigate(Routes.FeeSettings) }
            SettingsButtonRow("Channel Orders") { navController.navigate(Routes.ChannelOrdersSettings) }
            SettingsButtonRow("LDK Debug") { navController.navigate(Routes.LdkDebug) }

            SectionHeader("LOGS")
            SettingsButtonRow("Logs") { navController.navigate(Routes.Logs) }
            SettingsTextButtonRow(
                title = "Export Logs",
                onClick = {
                    viewModel.zipLogsForSharing { uri -> context.shareZipFile(uri) }
                }
            )
            SettingsTextButtonRow(
                title = "Wipe Logs",
                onClick = viewModel::wipeLogs
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
                    toaster.success(title = ToastText("Settings state reset"))
                }
            )
            SettingsTextButtonRow(
                title = "Reset All Activities",
                onClick = {
                    activity.removeAllActivities()
                    toaster.success(title = ToastText("Activities removed"))
                }
            )
            SettingsTextButtonRow(
                title = "Reset Backup State",
                onClick = {
                    viewModel.resetBackupState()
                    toaster.success(title = ToastText("Backup state reset"))
                }
            )
            SettingsTextButtonRow(
                title = "Reset Widgets State",
                onClick = {
                    viewModel.resetWidgetsState()
                    toaster.success(title = ToastText("Widgets state reset"))
                }
            )
            SettingsTextButtonRow(
                title = "Refresh Currency Rates",
                onClick = {
                    viewModel.refreshCurrencyRates()
                    toaster.success(title = ToastText("Currency rates refreshed"))
                }
            )
            SettingsTextButtonRow(
                title = "Reset App Database",
                onClick = {
                    viewModel.resetDatabase()
                    toaster.success(title = ToastText("Database state reset"))
                }
            )
            SettingsTextButtonRow(
                title = "Reset Blocktank State",
                onClick = {
                    viewModel.resetBlocktankState()
                    toaster.success(title = ToastText("Blocktank state reset"))
                }
            )
            SettingsTextButtonRow(
                title = "Reset Cache Store",
                onClick = {
                    viewModel.resetCacheStore()
                    toaster.success(title = ToastText("Cache store reset"))
                }
            )
            SettingsTextButtonRow(
                title = "Wipe App",
                onClick = {
                    viewModel.wipeWallet()
                    toaster.success(title = ToastText("Wallet wiped"))
                }
            )

            SectionHeader("DEBUG")

            SettingsTextButtonRow(
                title = "Generate Test Activities",
                onClick = {
                    val count = 100
                    activity.generateRandomTestData(count)
                    toaster.success(title = ToastText("Generated $count test activities"))
                }
            )
            SettingsTextButtonRow(
                "Fake New BG Receive",
                onClick = {
                    viewModel.fakeBgReceive()
                    toaster.info(title = ToastText("Restart app to see the payment received sheet"))
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
