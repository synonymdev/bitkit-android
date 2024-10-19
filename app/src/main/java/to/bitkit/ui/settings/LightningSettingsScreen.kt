package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ui.MainUiState
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.CopyToClipboardButton
import to.bitkit.ui.shared.FullWidthTextButton
import to.bitkit.ui.shared.InfoField
import to.bitkit.ui.shared.Payments
import to.bitkit.ui.shared.Peers

@Composable
fun LightningSettingsScreen(viewModel: WalletViewModel) {
    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
    val contentState = uiState.value.asContent() ?: return

    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        NodeDetails(contentState)
        WalletDetails(contentState)
        Peers(contentState.peers, viewModel::disconnectPeer)
        Payments(viewModel)
        Channels(
            contentState.channels,
            contentState.peers.isNotEmpty(),
            viewModel::openChannel,
            viewModel::closeChannel,
        )
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Blocktank",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            HorizontalDivider()
            FullWidthTextButton(viewModel::registerForNotifications) { Text("Register for notifications") }
            FullWidthTextButton(viewModel::debugLspNotifications) { Text("Self test notification") }
        }
        Spacer(modifier = Modifier.height(1.dp))
    }
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
        value = contentState.btcAddress,
        label = stringResource(R.string.address),
        maxLength = 44,
        trailingIcon = { CopyToClipboardButton(contentState.btcAddress) },
    )
}
