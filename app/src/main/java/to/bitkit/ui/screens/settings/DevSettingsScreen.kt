package to.bitkit.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.flags.PaykitFeatureFlags
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsSwitchRow
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.navigateTo
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
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
    val isPaykitEnabled by settings.isPaykitEnabled.collectAsStateWithLifecycle()
    var showPaykitWarning by remember { mutableStateOf(false) }

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
            SettingsButtonRow("Fee Settings") { navController.navigateTo(Routes.FeeSettings) }
            SettingsButtonRow("Channel Orders") { navController.navigateTo(Routes.ChannelOrdersSettings) }
            SettingsButtonRow("LDK") { navController.navigateTo(Routes.LdkDebug) }
            SettingsButtonRow("VSS") { navController.navigateTo(Routes.VssDebug) }
            SettingsButtonRow("Probing Tool") { navController.navigateTo(Routes.ProbingTool) }

            SectionHeader("RECOVERY")
            SettingsButtonRow("Legacy RN Close Recovery") { navController.navigateTo(Routes.LegacyRnRecovery) }

            if (PaykitFeatureFlags.isUiAvailable) {
                SectionHeader("PAYKIT")
                SettingsSwitchRow(
                    title = "Enable Paykit UI",
                    isChecked = isPaykitEnabled,
                    onClick = {
                        if (isPaykitEnabled) {
                            settings.setIsPaykitEnabled(false)
                            app.toast(
                                type = Toast.ToastType.SUCCESS,
                                title = "Paykit UI disabled",
                                testTag = "PaykitUiDisabledToast",
                            )
                        } else {
                            showPaykitWarning = true
                        }
                    },
                    switchTestTag = "PaykitUiToggle",
                )
            }

            SectionHeader("HARDWARE WALLET")
            SettingsButtonRow("Trezor") { navController.navigateTo(Routes.Trezor) }

            SectionHeader("LOGS")
            SettingsButtonRow("Logs") { navController.navigateTo(Routes.Logs) }
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

                SettingsButtonRow("Blocktank Regtest") { navController.navigateTo(Routes.RegtestSettings) }
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
                title = "Reset App Database",
                onClick = {
                    viewModel.resetDatabase()
                    app.toast(type = Toast.ToastType.SUCCESS, title = "Database state reset")
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
                "Fake New BG Receive",
                onClick = {
                    viewModel.fakeBgReceive()
                    app.toast(type = Toast.ToastType.INFO, title = "Restart app to see the payment received sheet")
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

    if (showPaykitWarning && PaykitFeatureFlags.isUiAvailable) {
        AlertDialog(
            onDismissRequest = { showPaykitWarning = false },
            title = { Text("Enable Paykit UI?") },
            text = {
                Text(
                    "Paykit features are still experimental and may not work reliably until supporting homeserver " +
                        "changes are deployed."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        settings.setIsPaykitEnabled(true)
                        showPaykitWarning = false
                        app.toast(
                            type = Toast.ToastType.SUCCESS,
                            title = "Paykit UI enabled",
                            testTag = "PaykitUiEnabledToast",
                        )
                    },
                ) {
                    Text("Enable")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPaykitWarning = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
