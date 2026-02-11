package to.bitkit.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.synonym.vssclient.KeyVersion
import com.synonym.vssclient.LdkNamespace
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.BackupCategory
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.settings.SectionFooter
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.shared.util.shareFile
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
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
        onListVssKeys = viewModel::listVssKeys,
        onDeleteVssKey = viewModel::deleteVssKey,
        onDeleteAllVssKeys = viewModel::deleteAllVssKeys,
        onDeleteVssLdkKey = viewModel::deleteVssLdkKey,
        onListVssLdkKeys = viewModel::listVssLdkKeys,
        onShareVssLdkKey = viewModel::shareVssLdkKey,
        onRestartNode = viewModel::restartNode,
    )
}

@Suppress("CyclomaticComplexMethod")
@Composable
private fun LdkDebugContent(
    uiState: LdkDebugUiState,
    onBackClick: () -> Unit,
    onNodeUriChange: (String) -> Unit,
    onAddPeer: () -> Unit,
    onPasteAndAddPeer: () -> Unit,
    onLogNetworkGraphInfo: () -> Unit,
    onExportNetworkGraph: (onFileReady: (File) -> Unit) -> Unit,
    onListVssKeys: () -> Unit,
    onDeleteVssKey: (String) -> Unit,
    onDeleteAllVssKeys: () -> Unit,
    onDeleteVssLdkKey: (String, LdkNamespace) -> Unit,
    onListVssLdkKeys: () -> Unit,
    onShareVssLdkKey: (String, LdkNamespace, (File) -> Unit) -> Unit,
    onRestartNode: () -> Unit,
) {
    val context = LocalContext.current
    var showDeleteAllConfirmation by remember { mutableStateOf(false) }
    var selectedVssTab by remember { mutableIntStateOf(0) }

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

            SectionHeader("VSS")
            CustomTabRowWithSpacing(
                tabs = VssTab.entries,
                currentTabIndex = selectedVssTab,
                onTabChange = { selectedVssTab = it.ordinal },
            )
            when (VssTab.entries[selectedVssTab]) {
                VssTab.APP -> {
                    SettingsTextButtonRow(
                        title = "List Keys",
                        iconRes = R.drawable.ic_stack,
                        iconSize = 24.dp,
                        value = if (uiState.vssKeys.isNotEmpty()) "${uiState.vssKeys.size} found" else "",
                        enabled = !uiState.isLoading,
                        onClick = onListVssKeys,
                    )
                    AnimatedVisibility(
                        visible = uiState.vssKeys.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            uiState.vssKeys.forEachIndexed { index, keyVersion ->
                                SettingsTextButtonRow(
                                    title = "${index + 1}. v${keyVersion.version}",
                                    description = keyVersion.key,
                                    iconRes = R.drawable.ic_tag,
                                    iconSize = 24.dp,
                                    enabled = !uiState.isLoading,
                                    height = 44.dp,
                                    trailingContent = {
                                        SecondaryButton(
                                            text = null,
                                            onClick = { onDeleteVssKey(keyVersion.key) },
                                            enabled = !uiState.isLoading,
                                            size = ButtonSize.Small,
                                            fullWidth = false,
                                            icon = {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_trash),
                                                    contentDescription = "Delete key",
                                                    tint = Colors.Red,
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                    SettingsTextButtonRow(
                        title = "Delete All",
                        iconRes = R.drawable.ic_trash,
                        iconSize = 24.dp,
                        enabled = !uiState.isLoading && uiState.vssKeys.isNotEmpty(),
                        onClick = { showDeleteAllConfirmation = true },
                    )
                }

                VssTab.LDK -> {
                    SettingsTextButtonRow(
                        title = "List Keys",
                        iconRes = R.drawable.ic_stack,
                        iconSize = 24.dp,
                        value = if (uiState.vssLdkKeys.isNotEmpty()) "${uiState.vssLdkKeys.size} found" else "",
                        enabled = !uiState.isLoading,
                        onClick = onListVssLdkKeys,
                    )
                    AnimatedVisibility(
                        visible = uiState.vssLdkKeys.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column {
                            uiState.vssLdkKeys.forEachIndexed { index, item ->
                                SettingsTextButtonRow(
                                    title = "${index + 1}. v${item.keyVersion.version}",
                                    description = item.keyVersion.key,
                                    iconRes = R.drawable.ic_tag,
                                    iconSize = 24.dp,
                                    enabled = !uiState.isLoading,
                                    height = 44.dp,
                                    trailingContent = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            SecondaryButton(
                                                text = null,
                                                onClick = {
                                                    onShareVssLdkKey(item.keyVersion.key, item.namespace) { file ->
                                                        val uri = FileProvider.getUriForFile(
                                                            context,
                                                            Env.FILE_PROVIDER_AUTHORITY,
                                                            file,
                                                        )
                                                        context.shareFile(uri, "application/octet-stream")
                                                    }
                                                },
                                                enabled = !uiState.isLoading,
                                                size = ButtonSize.Small,
                                                fullWidth = false,
                                                icon = {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_share),
                                                        contentDescription = "Share key",
                                                    )
                                                },
                                            )
                                            SecondaryButton(
                                                text = null,
                                                onClick = { onDeleteVssLdkKey(item.keyVersion.key, item.namespace) },
                                                enabled = !uiState.isLoading,
                                                size = ButtonSize.Small,
                                                fullWidth = false,
                                                icon = {
                                                    Icon(
                                                        painter = painterResource(R.drawable.ic_trash),
                                                        contentDescription = "Delete key",
                                                        tint = Colors.Red,
                                                    )
                                                },
                                            )
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }

            SectionHeader("NODE")
            SettingsTextButtonRow(
                title = "Restart",
                iconRes = R.drawable.ic_arrow_clockwise,
                iconSize = 24.dp,
                enabled = !uiState.isLoading,
                onClick = onRestartNode,
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showDeleteAllConfirmation) {
        AppAlertDialog(
            title = "Delete All VSS Keys?",
            text = "This will permanently delete all ${uiState.vssKeys.size} VSS key(s). This action cannot be undone.",
            confirmText = "Delete All",
            onConfirm = {
                showDeleteAllConfirmation = false
                onDeleteAllVssKeys()
            },
            onDismiss = { showDeleteAllConfirmation = false },
        )
    }
}

private enum class VssTab : TabItem {
    APP, LDK;

    override val uiText: String
        @Composable get() = when (this) {
            APP -> "App"
            LDK -> "LDK"
        }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    val vssKeys = BackupCategory.entries.mapIndexed { i, key -> KeyVersion(key.name, (i + 1).toLong()) }
    var uiState by remember {
        mutableStateOf(
            LdkDebugUiState(
                // vssKeys = vssKeys,
            )
        )
    }

    fun listVssKeys() {
        uiState = uiState.copy(vssKeys = vssKeys)
    }

    AppThemeSurface {
        LdkDebugContent(
            uiState = uiState,
            onBackClick = {},
            onNodeUriChange = {},
            onAddPeer = {},
            onPasteAndAddPeer = {},
            onLogNetworkGraphInfo = {},
            onExportNetworkGraph = {},
            onListVssKeys = ::listVssKeys,
            onDeleteVssKey = {},
            onDeleteAllVssKeys = {},
            onDeleteVssLdkKey = { _, _ -> },
            onListVssLdkKeys = {},
            onShareVssLdkKey = { _, _, _ -> },
            onRestartNode = {},
        )
    }
}
