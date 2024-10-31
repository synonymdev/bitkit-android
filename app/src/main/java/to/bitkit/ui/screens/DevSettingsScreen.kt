package to.bitkit.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ext.requiresPermission
import to.bitkit.ext.toast
import to.bitkit.ui.MainUiState
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.postNotificationsPermission
import to.bitkit.ui.pushNotification
import to.bitkit.ui.scaffold.AppScaffold
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.CopyToClipboardButton
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.shared.InfoField
import to.bitkit.ui.shared.Orders
import to.bitkit.ui.shared.Payments
import to.bitkit.ui.shared.Peers

@Composable
fun DevSettingsScreen(
    viewModel: WalletViewModel,
    navController: NavController,
) = AppScaffold(navController, viewModel, "Dev Settings") {
    val state = viewModel.uiState.collectAsStateWithLifecycle()
    val uiState = state.value.asContent() ?: return@AppScaffold
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        NodeDetails(uiState)
        WalletDetails(uiState)
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
            FullWidthTextButton(viewModel::debugActivityItems) { Text("Activity Items") }
            FullWidthTextButton(viewModel::debugBlocktankInfo) { Text("Blocktank Info API") }
            FullWidthTextButton(viewModel::debugTransactionSheet) { Text("Fake New BG Transaction") }
            HorizontalDivider()
            NotificationButton()
            FullWidthTextButton(viewModel::registerForNotifications) { Text("1. Register Device for Notifications") }
            FullWidthTextButton(viewModel::debugLspNotifications) { Text("2. Test Remote Notification") }
        }
        Orders(uiState.orders, viewModel)
    }
}

@Composable
fun NotificationButton() {
    val context = LocalContext.current
    var canPush by remember {
        mutableStateOf(!context.requiresPermission(postNotificationsPermission))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        canPush = it
        toast("Permission ${if (it) "Granted" else "Denied"}")
    }

    val onClick = {
        if (context.requiresPermission(postNotificationsPermission)) {
            permissionLauncher.launch(postNotificationsPermission)
        } else {
            pushNotification(
                title = "Bitkit Notification",
                text = "Short custom notification description",
                bigText = "Much longer text that cannot fit one line " + "because the lightning channel has been updated " + "via a push notification bro…",
            )
        }
        Unit
    }
    val text by remember {
        derivedStateOf { if (canPush) "Test Local Notification" else "Enable Notification Permissions" }
    }
    FullWidthTextButton(onClick = onClick) { Text(text = text) }
}

@Composable
fun NodeDetails(contentState: MainUiState.Content) {
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
                text = contentState.nodeLifecycleState.name,
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

@Composable
fun WalletDetails(
    contentState: MainUiState.Content,
) {
    InfoField(
        value = contentState.onchainAddress,
        label = stringResource(R.string.address),
        maxLength = 44,
        trailingIcon = { CopyToClipboardButton(contentState.onchainAddress) },
    )
}
