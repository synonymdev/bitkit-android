package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.env.LnPeer
import to.bitkit.env.LnPeers
import to.bitkit.ext.toast
import to.bitkit.ui.MainUiState
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.InfoField
import to.bitkit.ui.shared.Peers

@Composable
fun PeersScreen(
    viewModel: WalletViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier,
    ) {
        Card {
            Text("⚠️ Please return to Home screen to see your updates…", Modifier.padding(12.dp))
        }
        var pubKey by remember { mutableStateOf(LnPeers.local.nodeId) }
        val host by remember { mutableStateOf(LnPeers.local.host) }
        var port by remember { mutableStateOf(LnPeers.local.port) }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Connect to a Peer",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                label = { Text("Pubkey") },
                value = pubKey,
                onValueChange = { pubKey = it },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            InfoField(value = host, label = "Host", maxLength = 72)
            OutlinedTextField(
                label = { Text("Port") },
                value = port,
                onValueChange = { port = it },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                viewModel.connectPeer(LnPeer(pubKey, host, port))
                toast("Peer connected.")
            }) {
                Text(stringResource(R.string.connect))
            }
        }
        val peers = remember { viewModel.uiState.value.asContent()?.peers.orEmpty() }
        Peers(peers, viewModel::disconnectPeer)
    }
}
