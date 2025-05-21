package to.bitkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.Network
import to.bitkit.R
import to.bitkit.currentActivity
import to.bitkit.env.Env
import to.bitkit.ui.activityListViewModel
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsButtonRow
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.navigateToChannelOrdersSettings
import to.bitkit.ui.navigateToLightning
import to.bitkit.ui.navigateToLogs
import to.bitkit.ui.navigateToRegtestSettings
import to.bitkit.ui.pushNotification
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun DevSettingsScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val activity = activityListViewModel ?: return

    ScreenColumn {
        AppTopBar(stringResource(R.string.settings__dev_title), onBackClick = { navController.popBackStack() })
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
                SettingsTextButtonRow(stringResource(R.string.security__wipe_app)) { viewModel.wipeStorage() }
            }

            SectionHeader(title = stringResource(R.string.settings__adv__section_other))
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Channels(uiState.channels, uiState.peers.isNotEmpty(), viewModel::openChannel, viewModel::closeChannel)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Debug",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                    FullWidthTextButton(viewModel::debugDb) { Text("Database") }
                    FullWidthTextButton(viewModel::debugKeychain) { Text("Keychain") }
                    FullWidthTextButton(viewModel::debugFcmToken) { Text("Print FCM Token") }
                    FullWidthTextButton(viewModel::debugMnemonic) { Text("⚠️ Print Mnemonic") }
                    FullWidthTextButton(viewModel::wipeStorage) { Text("Wipe Wallet") }
                    FullWidthTextButton(viewModel::debugBlocktankInfo) { Text("Blocktank Info API") }
                    FullWidthTextButton(viewModel::debugTransactionSheet) { Text("Fake New BG Transaction") }
                    HorizontalDivider()
                    FullWidthTextButton(::debugPushNotification) { Text("Test Local Notification") }
                    FullWidthTextButton(viewModel::manualRegisterForNotifications) { Text("1. Register Device for Notifications") }
                    FullWidthTextButton(viewModel::debugLspNotifications) { Text("2. Test Remote Notification") }
                    HorizontalDivider()
                    val currency = currencyViewModel
                    FullWidthTextButton({ currency?.triggerRefresh() }) { Text("Refresh Currency Rates") }
                }
            }
        }
    }
}

private fun debugPushNotification() {
    pushNotification(
        title = "Bitkit Notification",
        text = "Short custom notification description",
        bigText = "Much longer text that cannot fit one line " + "because the lightning channel has been updated " + "via a push notification bro…",
        context = requireNotNull(currentActivity())
    )
}
