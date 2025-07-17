package to.bitkit.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.walletViewModel

@Composable
fun DevSettingsScreen(
    navController: NavController,
) {
    val wallet = walletViewModel ?: return
    val activity = activityListViewModel ?: return
    val currency = currencyViewModel ?: return
    val settings = settingsViewModel ?: return
    val app = appViewModel ?: return

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

            if (Env.network == Network.REGTEST) {
                SectionHeader(title = "REGTEST")

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
            SettingsTextButtonRow("Log FCM Token") { wallet.debugFcmToken() }
            SettingsTextButtonRow("Log Blocktank Info") { wallet.debugBlocktankInfo() }
            SettingsTextButtonRow("Fake New BG Transaction") { wallet.debugTransactionSheet() }
            SettingsTextButtonRow("Open channel to trusted peer", onClick = wallet::openChannel)

            SectionHeader("NOTIFICATIONS")
            SettingsTextButtonRow("Register for notifications with LSP", onClick = wallet::manualRegisterForNotifications)
            SettingsTextButtonRow("Self test notification from LSP", onClick = wallet::debugLspNotifications)

        }
    }
}
