package to.bitkit.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.synonym.vssclient.LdkNamespace
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.BackupCategory
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.shared.util.shareFile
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes
import to.bitkit.viewmodels.VssDebugUiState
import to.bitkit.viewmodels.VssDebugViewModel
import to.bitkit.viewmodels.VssLdkKeyItem
import java.io.File

@Composable
fun VssDebugScreen(
    navController: NavController,
    viewModel: VssDebugViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    VssDebugContent(
        uiState = uiState,
        onBackClick = { navController.popBackStack() },
        onRefresh = viewModel::refreshAllKeys,
        onListVssKeys = viewModel::listVssKeys,
        onDeleteVssKey = viewModel::deleteVssKey,
        onDeleteAllVssKeys = viewModel::deleteAllVssKeys,
        onListVssLdkKeys = viewModel::listVssLdkKeys,
        onDeleteVssLdkKey = viewModel::deleteVssLdkKey,
        onShareVssLdkKey = viewModel::shareVssLdkKey,
    )
}

@Composable
private fun VssDebugContent(
    uiState: VssDebugUiState,
    onBackClick: () -> Unit,
    onRefresh: () -> Unit,
    onListVssKeys: () -> Unit,
    onDeleteVssKey: (String) -> Unit,
    onDeleteAllVssKeys: () -> Unit,
    onListVssLdkKeys: () -> Unit,
    onDeleteVssLdkKey: (String, LdkNamespace) -> Unit,
    onShareVssLdkKey: (String, LdkNamespace, (File) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var showDeleteAllConfirmation by remember { mutableStateOf(false) }
    var selectedVssTab by remember { mutableIntStateOf(0) }

    ScreenColumn {
        AppTopBar(
            titleText = "VSS Debug",
            onBackClick = onBackClick,
            actions = {
                IconButton(
                    onClick = onRefresh,
                    enabled = !uiState.isLoading,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_clockwise),
                        contentDescription = "Refresh",
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            CustomTabRowWithSpacing(
                tabs = VssTab.entries,
                currentTabIndex = selectedVssTab,
                onTabChange = { selectedVssTab = it.ordinal },
            )
            when (VssTab.entries[selectedVssTab]) {
                VssTab.APP -> {
                    ListButton(
                        text = if (uiState.vssKeys.isEmpty()) "List Keys" else "List Keys (Refresh)",
                        enabled = !uiState.isLoading,
                        onClick = onListVssKeys,
                    )
                    AnimatedVisibility(
                        visible = uiState.vssKeys.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.vssKeys.forEach { keyVersion ->
                                ListCard(
                                    title = keyVersion.key,
                                    subtitle = "v${keyVersion.version}",
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
                    AnimatedVisibility(
                        visible = uiState.vssKeys.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        ListButton(
                            text = "Delete All",
                            enabled = !uiState.isLoading,
                            onClick = { showDeleteAllConfirmation = true },
                        )
                    }
                }

                VssTab.LDK -> {
                    ListButton(
                        text = if (uiState.vssLdkKeys.isEmpty()) "List Keys" else "List Keys (Refresh)",
                        enabled = !uiState.isLoading,
                        onClick = onListVssLdkKeys,
                    )
                    AnimatedVisibility(
                        visible = uiState.vssLdkKeys.isNotEmpty(),
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            uiState.vssLdkKeys.forEach { item ->
                                ListCard(
                                    title = item.keyVersion.key,
                                    subtitle = "${item.namespace.displayName} (v${item.keyVersion.version})",
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

@Composable
private fun ListButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TertiaryButton(
        text = text,
        onClick = onClick,
        enabled = enabled,
        fullWidth = true,
        size = ButtonSize.Small,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun ListCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(color = Colors.Gray6, shape = Shapes.medium)
            .padding(16.dp)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f)
        ) {
            BodySSB(text = title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Caption(text = subtitle, color = Colors.White64)
        }
        trailingContent?.invoke()
    }
}

private val LdkNamespace.displayName: String
    get() = when (this) {
        LdkNamespace.Default -> "default"
        LdkNamespace.Monitors -> "monitors"
        LdkNamespace.ArchivedMonitors -> "archivedMonitors"
        else -> toString()
    }

private enum class VssTab : TabItem {
    APP, LDK;

    override val uiText: String @Composable get() = this.name
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true)
@Composable
private fun PreviewApp() {
    val vssKeys = BackupCategory.entries.mapIndexed { i, key ->
        com.synonym.vssclient.KeyVersion(key.name, (i + 1).toLong())
    }
    var uiState by remember {
        mutableStateOf(VssDebugUiState(vssKeys = vssKeys))
    }

    AppThemeSurface {
        VssDebugContent(
            uiState = uiState,
            onBackClick = {},
            onRefresh = {},
            onListVssKeys = { uiState = uiState.copy(vssKeys = vssKeys) },
            onDeleteVssKey = {},
            onDeleteAllVssKeys = {},
            onListVssLdkKeys = {},
            onDeleteVssLdkKey = { _, _ -> },
            onShareVssLdkKey = { _, _, _ -> },
        )
    }
}

@Suppress("SpellCheckingInspection")
@Preview(showSystemUi = true)
@Composable
private fun PreviewLdk() {
    val vssLdkKeys = listOf(
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("events", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("output_sweeper", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("scorer", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("external_pathfinding_scores_cache", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("peers", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("vss_schema_version", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("node_metrics", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("manager", 1), LdkNamespace.Default),
        VssLdkKeyItem(
            com.synonym.vssclient.KeyVersion(
                "78e33351f6fbf65d041108cc371e793ff5a0006366ab442e92caea83b2a3838b_0",
                1,
            ),
            LdkNamespace.Monitors,
        ),
    )
    var uiState by remember {
        mutableStateOf(VssDebugUiState(vssLdkKeys = vssLdkKeys))
    }

    AppThemeSurface {
        VssDebugContent(
            uiState = uiState,
            onBackClick = {},
            onRefresh = {},
            onListVssKeys = {},
            onDeleteVssKey = {},
            onDeleteAllVssKeys = {},
            onListVssLdkKeys = { uiState = uiState.copy(vssLdkKeys = vssLdkKeys) },
            onDeleteVssLdkKey = { _, _ -> },
            onShareVssLdkKey = { _, _, _ -> },
        )
    }
}
