package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import to.bitkit.PEER
import to.bitkit.R
import to.bitkit.ui.MainViewModel

@Composable
fun PeersScreen(
    viewModel: MainViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        var pubKey by remember { mutableStateOf(PEER.nodeId) }
        var port by remember { mutableStateOf(PEER.port) }

        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            OutlinedTextField(
                label = { Text("Port") },
                value = port,
                onValueChange = { port = it },
                textStyle = MaterialTheme.typography.labelSmall,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { viewModel.connectPeer(pubKey, port) }) {
                Text(stringResource(R.string.connect))
            }
        }
        ConnectedPeers(viewModel.peers)
    }
}

@Composable
private fun ConnectedPeers(peers: List<String>) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row {
            Text(
                text = "Connected Peers",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "${peers.size}",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            peers.sorted().reversed().forEachIndexed { i, it ->
                if (i > 0 && peers.size > 1) {
                    HorizontalDivider()
                }
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        }
    }
}
