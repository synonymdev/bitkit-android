package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import to.bitkit.ui.MainViewModel
import to.bitkit.ui.shared.Channels

@Composable
fun ChannelsScreen(
    viewModel: MainViewModel,
) {
    Spacer(modifier = Modifier.size(48.dp))
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    viewModel.openChannel()
                },
                enabled = viewModel.peers.isNotEmpty()
            ) {
                Text("Open Channel")
            }
        }
        Channels(
            channels = viewModel.channels,
            onChannelClose = viewModel::closeChannel,
        )
    }
}
