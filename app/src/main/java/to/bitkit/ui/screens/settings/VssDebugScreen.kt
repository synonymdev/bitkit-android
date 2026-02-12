package to.bitkit.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.settings.SettingsTextButtonRow
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.activity.components.TabItem
import to.bitkit.ui.shared.util.shareFile
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
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
                            uiState.vssKeys.forEach { keyVersion ->
                                SettingsTextButtonRow(
                                    title = keyVersion.key,
                                    description = "v${keyVersion.version}",
                                    iconRes = R.drawable.ic_note,
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
                            uiState.vssLdkKeys.forEach { item ->
                                SettingsTextButtonRow(
                                    title = item.keyVersion.key,
                                    description = "${item.namespace.displayName} (v${item.keyVersion.version})",
                                    iconRes = R.drawable.ic_note,
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

private val LdkNamespace.displayName: String
    get() = when (this) {
        LdkNamespace.Default -> "default"
        LdkNamespace.Monitors -> "monitors"
        LdkNamespace.ArchivedMonitors -> "archivedMonitors"
        else -> toString()
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
    val vssKeys = BackupCategory.entries.mapIndexed { i, key ->
        com.synonym.vssclient.KeyVersion(key.name, (i + 1).toLong())
    }
    val vssLdkKeys = listOf(
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("manager", 1), LdkNamespace.Default),
        VssLdkKeyItem(com.synonym.vssclient.KeyVersion("3c14ccafc88ad68e3705d59f_0", 1), LdkNamespace.Monitors),
    )
    var uiState by remember {
        mutableStateOf(
            VssDebugUiState(
                vssKeys = vssKeys,
                vssLdkKeys = vssLdkKeys,
            )
        )
    }

    AppThemeSurface {
        VssDebugContent(
            uiState = uiState,
            onBackClick = {},
            onRefresh = {},
            onListVssKeys = { uiState = uiState.copy(vssKeys = vssKeys) },
            onDeleteVssKey = {},
            onDeleteAllVssKeys = {},
            onListVssLdkKeys = { uiState = uiState.copy(vssLdkKeys = vssLdkKeys) },
            onDeleteVssLdkKey = { _, _ -> },
            onShareVssLdkKey = { _, _, _ -> },
        )
    }
}
