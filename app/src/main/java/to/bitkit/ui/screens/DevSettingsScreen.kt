package to.bitkit.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.currentActivity
import to.bitkit.ui.currencyViewModel
import to.bitkit.ui.pushNotification
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.CopyToClipboardButton
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.shared.InfoField
import to.bitkit.ui.shared.Payments
import to.bitkit.ui.shared.Peers
import to.bitkit.viewmodels.MainUiState
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun DevSettingsScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ScreenColumn {
        AppTopBar(stringResource(R.string.dev_settings), onBackClick = { navController.popBackStack() })
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            NodeDetails(uiState)
            InfoField(
                value = uiState.onchainAddress,
                label = stringResource(R.string.wallet__activity_address),
                maxLength = 36,
                trailingIcon = {
                    Row {
                        CopyToClipboardButton(uiState.onchainAddress)
                        IconButton(onClick = viewModel::manualNewAddress) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
            )

            Peers(uiState.peers, viewModel::disconnectPeer)
            Channels(uiState.channels, uiState.peers.isNotEmpty(), viewModel::openChannel, viewModel::closeChannel)
            Payments(viewModel)
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

private fun debugPushNotification() {
    pushNotification(
        title = "Bitkit Notification",
        text = "Short custom notification description",
        bigText = "Much longer text that cannot fit one line " + "because the lightning channel has been updated " + "via a push notification bro…",
        context = requireNotNull(currentActivity())
    )
}

@Composable
fun NodeDetails(contentState: MainUiState) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier
                .padding(16.dp)
                .padding(bottom = 0.dp)
        ) {
            Text(
                text = "Node",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = contentState.nodeLifecycleState.displayState,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        InfoField(
            value = contentState.nodeId,
            label = stringResource(R.string.node_id),
            maxLength = 44,
            trailingIcon = { CopyToClipboardButton(contentState.nodeId) },
        )
    }
}
