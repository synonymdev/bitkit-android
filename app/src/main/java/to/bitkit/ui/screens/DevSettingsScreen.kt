package to.bitkit.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.models.Toast
import to.bitkit.ui.Routes
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun DevSettingsScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    val activity = activityListViewModel ?: return
    val currency = currencyViewModel ?: return
    val settings = settingsViewModel ?: return
    val app = appViewModel ?: return

    val selectedNetwork by settings.selectedNetwork.collectAsStateWithLifecycle()

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
            SettingsButtonRow("Channel Orders") { Routes.ChannelOrdersSettings }

            if (selectedNetwork == Network.REGTEST) {
                SectionHeader(title = "REGTEST ONLY")

                SettingsButtonRow("Blocktank Regtest") { navController.navigate(Routes.RegtestSettings) }
                SettingsTextButtonRow("Generate Test Activities") { activity.generateRandomTestData() }
            }

            SectionHeader(title = "APP CACHE")
            SettingsTextButtonRow("Reset Settings Store") {
                settings.reset()
                app.toast(type = Toast.ToastType.SUCCESS, title = "Settings store reset")
            }
            SettingsTextButtonRow("Reset All Activities") {
                activity.removeAllActivities()
                app.toast(type = Toast.ToastType.SUCCESS, title = "Activities removed")
            }
            SettingsTextButtonRow("Refresh Currency Rates") {
                currency.triggerRefresh()
                app.toast(type = Toast.ToastType.SUCCESS, title = "Currency rates refreshed")
            }

            SectionHeader(title = "DEBUG")
            SettingsTextButtonRow("Log FCM Token") { viewModel.debugFcmToken() }
            SettingsTextButtonRow("Log Blocktank Info") { viewModel.debugBlocktankInfo() }
            SettingsTextButtonRow("Fake New BG Transaction") { viewModel.debugTransactionSheet() }

            SectionHeader("Blocktank")
            SettingsTextButtonRow("Register for notifications", onClick = viewModel::manualRegisterForNotifications)
            SettingsTextButtonRow("Self test notification", onClick = viewModel::debugLspNotifications)
            SettingsTextButtonRow("Open channel to trusted peer", onClick = viewModel::openChannel)

        }
    }
}
