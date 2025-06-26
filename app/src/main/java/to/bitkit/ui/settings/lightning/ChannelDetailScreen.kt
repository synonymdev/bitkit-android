package to.bitkit.ui.settings.lightning

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.ext.createChannelDetails
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Title
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun ChannelDetailScreen(
    navController: NavController,
    viewModel: LightningConnectionsViewModel,
) {
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearSelectedChannel()
        }
    }

    val selectedChannel by viewModel.selectedChannel.collectAsStateWithLifecycle()
    val channel = selectedChannel ?: return

    Content(
        channel = channel,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
    )
}

@Composable
private fun Content(
    channel: ChannelUi,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__connection),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .weight(1f)
                .navigationBarsPadding()
                // .verticalScroll(rememberScrollState())
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Column {
                    Title("TODO: Channel Detail Screen")
                    Caption("Channel ID: ${channel.details.channelId}")
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            channel = ChannelUi(
                name = "Connection 1",
                details = createChannelDetails().copy(
                    channelId = "channel_1",
                    channelValueSats = 100_000_000u,
                    outboundCapacityMsat = 25_000_000u,
                    inboundCapacityMsat = 750_000_000u,
                    fundingTxo = OutPoint(txid = "sample_txid", vout = 0u),
                ),
            ),
        )
    }
}
