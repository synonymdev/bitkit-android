package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = { viewModel.openChannel() },
                enabled = viewModel.peers.isNotEmpty()
            ) {
                Text("Open Channel")
            }
        }
        Channels(viewModel.channels, viewModel::closeChannel)
        PayInvoice(viewModel::payInvoice)
    }
}
