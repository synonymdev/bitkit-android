package to.bitkit.ui.screens.transfer.external

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.ext.ellipsisMiddle
import to.bitkit.models.LnPeer
import to.bitkit.ui.Routes
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun LnurlChannelScreen(
    route: Routes.LnurlChannel,
    onConnected: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    viewModel: LnurlChannelViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.init(route)
    }

    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected) {
            onConnected()
        }
    }

    Content(
        uiState = uiState,
        onBack = onBack,
        onClose = onClose,
        onConnect = { viewModel.onConnect() },
        onCancel = onClose,
    )
}

@Composable
private fun Content(
    uiState: LnurlChannelUiState,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onConnect: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.other__lnurl_channel_header),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            VerticalSpacer(32.dp)

            Display(stringResource(R.string.other__lnurl_channel_title).withAccent(accentColor = Colors.Purple))
            VerticalSpacer(8.dp)

            val peer = uiState.peer
            if(peer != null) {
                BodyM(text = stringResource(R.string.other__lnurl_channel_message), color = Colors.White64)
                VerticalSpacer(48.dp)

                Caption13Up(text = stringResource(R.string.other__lnurl_channel_lsp), color = Colors.White64)
                VerticalSpacer(16.dp)

                InfoRow(
                    label = stringResource(R.string.other__lnurl_channel_node),
                    value = peer.nodeId.ellipsisMiddle(24),
                )
                InfoRow(
                    label = stringResource(R.string.other__lnurl_channel_host),
                    value = peer.host,
                )
                InfoRow(
                    label = stringResource(R.string.other__lnurl_channel_port),
                    value = peer.port,
                )

                FillHeight()
                VerticalSpacer(32.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SecondaryButton(
                        text = stringResource(R.string.common__cancel),
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        text = stringResource(R.string.common__connect),
                        onClick = onConnect,
                        modifier = Modifier.weight(1f),
                        isLoading = uiState.isConnecting,
                        enabled = !uiState.isConnecting,
                    )
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp)
    ) {
        CaptionB(text = label)
        CaptionB(text = value)
    }
    HorizontalDivider()
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = LnurlChannelUiState(
                peer = LnPeer(
                    nodeId = "12345678901234567890123456789012345678901234567890",
                    host = "127.0.0.1",
                    port = "9735",
                )
            ),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewLoading() {
    AppThemeSurface {
        Content(
            uiState = LnurlChannelUiState(),
        )
    }
}
