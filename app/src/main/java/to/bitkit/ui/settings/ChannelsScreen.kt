package to.bitkit.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.ext.toast
import to.bitkit.ui.WalletViewModel
import to.bitkit.ui.shared.Channels
import to.bitkit.ui.shared.FullWidthTextButton

@Composable
fun ChannelsScreen(
    viewModel: WalletViewModel,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier,
    ) {
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val peers = uiState.asContent()?.peers.orEmpty()
        val channels = uiState.asContent()?.channels.orEmpty()
        Channels(channels, peers.isNotEmpty(), viewModel::openChannel, viewModel::closeChannel)
    }
}
