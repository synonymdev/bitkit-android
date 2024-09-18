package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.ui.MainUiState
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.Channels

@Composable
fun ChannelsScreen(
    viewModel: WalletViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier,
    ) {
        Card {
            Text("⚠️ Please return to Home screen to see your updates…", Modifier.padding(12.dp))
        }
        val peers = remember { (viewModel.uiState.value as? MainUiState.Content?)?.peers.orEmpty() }
        val channels = remember { (viewModel.uiState.value as? MainUiState.Content)?.channels.orEmpty() }
        Button(
            onClick = { viewModel.openChannel() },
            enabled = peers.isNotEmpty()
        ) {
            Text("Open Channel")
        }
        Channels(channels, viewModel::closeChannel)
        PayInvoice(viewModel::payInvoice)
    }
}
