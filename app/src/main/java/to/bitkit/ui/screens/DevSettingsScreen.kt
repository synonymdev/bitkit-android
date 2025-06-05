package to.bitkit.ui.screens

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
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToChannelOrdersSettings
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.navigateToLightning
import to.bitkit.ui.navigateToLogs
import to.bitkit.ui.navigateToRegtestSettings
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
            SettingsButtonRow("Lightning") { navController.navigateToLightning() }
            SettingsButtonRow("Channel Orders") { navController.navigateToChannelOrdersSettings() }
            SettingsButtonRow("Logs") { navController.navigateToLogs() }

            if (Env.network == Network.REGTEST) {
                SectionHeader(title = "REGTEST ONLY")

                SettingsButtonRow("Blocktank Regtest") { navController.navigateToRegtestSettings() }
                SettingsTextButtonRow("Reset All Activities") { activity.removeAllActivities() }
                SettingsTextButtonRow("Generate Test Activities") { activity.generateRandomTestData() }
                SettingsTextButtonRow("Reset Settings Store") { settings.reset() }
            }

            SectionHeader(title = "DEBUG")
            SettingsTextButtonRow("Log FCM Token") { viewModel.debugFcmToken() }
            SettingsTextButtonRow("Log Blocktank Info") { viewModel.debugBlocktankInfo() }
            SettingsTextButtonRow("Fake New BG Transaction") { viewModel.debugTransactionSheet() }
            SettingsTextButtonRow("Refresh Currency Rates") { currency.triggerRefresh() }
        }
    }
}
