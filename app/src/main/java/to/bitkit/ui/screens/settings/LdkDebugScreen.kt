package to.bitkit.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionFooter
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.shared.util.shareFile
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.LdkDebugUiState
import to.bitkit.viewmodels.LdkDebugViewModel
import java.io.File

@Composable
fun LdkDebugScreen(
    navController: NavController,
    viewModel: LdkDebugViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LdkDebugContent(
        uiState = uiState,
        onBackClick = { navController.popBackStack() },
        onNodeUriChange = viewModel::updateNodeUri,
        onAddPeer = viewModel::addPeer,
        onPasteAndAddPeer = viewModel::pasteAndAddPeer,
        onLogNetworkGraphInfo = viewModel::logNetworkGraphInfo,
        onExportNetworkGraph = viewModel::exportNetworkGraph,
        onRestartNode = viewModel::restartNode,
    )
}

@Composable
private fun LdkDebugContent(
    uiState: LdkDebugUiState,
    onBackClick: () -> Unit,
    onNodeUriChange: (String) -> Unit,
    onAddPeer: () -> Unit,
    onPasteAndAddPeer: () -> Unit,
    onLogNetworkGraphInfo: () -> Unit,
    onExportNetworkGraph: (onFileReady: (File) -> Unit) -> Unit,
    onRestartNode: () -> Unit,
) {
    val context = LocalContext.current

    ScreenColumn {
        AppTopBar(
            titleText = "LDK Debug",
            onBackClick = onBackClick,
            actions = { DrawerNavIcon() },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            SectionHeader("ADD PEER", padding = PaddingValues(0.dp))
            TextInput(
                value = uiState.nodeUri,
                onValueChange = onNodeUriChange,
                placeholder = "pubkey@host:port",
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                PrimaryButton(
                    text = "Add Peer",
                    onClick = onAddPeer,
                    enabled = !uiState.isLoading && uiState.nodeUri.isNotBlank(),
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    text = "Paste & Add",
                    onClick = onPasteAndAddPeer,
                    enabled = !uiState.isLoading,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f),
                )
            }

            SectionHeader("NETWORK GRAPH")
            SettingsTextButtonRow(
                title = "Log Graph Info",
                iconRes = R.drawable.ic_list,
                iconSize = 24.dp,
                enabled = !uiState.isLoading,
                onClick = onLogNetworkGraphInfo,
            )
            uiState.networkGraphInfo?.let { info ->
                SectionFooter(
                    "Nodes: ${info.nodeCount} | Channels: ${info.channelCount} | " +
                        "RGS sync: ${info.latestRgsSyncTimestamp ?: "-"}"
                )
            }
            SettingsTextButtonRow(
                title = "Export to File",
                iconRes = R.drawable.ic_share,
                iconSize = 24.dp,
                enabled = !uiState.isLoading,
                onClick = {
                    onExportNetworkGraph { file ->
                        val uri = FileProvider.getUriForFile(context, Env.FILE_PROVIDER_AUTHORITY, file)
                        context.shareFile(uri, "text/plain")
                    }
                },
            )

            SectionHeader("NODE")
            SettingsTextButtonRow(
                title = "Restart",
                iconRes = R.drawable.ic_arrow_clockwise,
                iconSize = 24.dp,
                enabled = !uiState.isLoading,
                onClick = onRestartNode,
            )

            VerticalSpacer(32.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    var uiState by remember { mutableStateOf(LdkDebugUiState()) }

    AppThemeSurface {
        LdkDebugContent(
            uiState = uiState,
            onBackClick = {},
            onNodeUriChange = { uiState = uiState.copy(nodeUri = it) },
            onAddPeer = {},
            onPasteAndAddPeer = {},
            onLogNetworkGraphInfo = {},
            onExportNetworkGraph = {},
            onRestartNode = {},
        )
    }
}
