package to.bitkit.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settingsViewModel
import to.bitkit.viewmodels.DevSettingsViewModel

@Composable
fun DevSettingsScreen(
    navController: NavController,
    viewModel: DevSettingsViewModel = hiltViewModel(),
) {
    val app = appViewModel ?: return
    val activity = activityListViewModel ?: return
    val currency = currencyViewModel ?: return
    val settings = settingsViewModel ?: return

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
            SettingsTextButtonRow("Fake BG Transaction", onClick = viewModel::fakeBgTransaction)
            SettingsTextButtonRow("Open channel to trusted peer", onClick = viewModel::openChannel)

            SectionHeader("NOTIFICATIONS")
            SettingsTextButtonRow("Register for LSP notifications", onClick = viewModel::registerForNotifications)
            SettingsTextButtonRow("Test notification from LSP ", onClick = viewModel::testLspNotification)
        }
    }
}
