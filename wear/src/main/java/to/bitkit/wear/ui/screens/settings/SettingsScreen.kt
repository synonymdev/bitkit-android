package to.bitkit.wear.ui.screens.settings

import android.text.format.DateUtils
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices
import to.bitkit.wear.ui.screens.activity.rememberNowSecs

@Composable
fun SettingsScreen(
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // Moves on every minute, so the import's age also grows while the page stays open.
    val syncedSecs = (uiState.phoneSyncedAtMs ?: 0) / DateUtils.SECOND_IN_MILLIS
    Content(
        uiState = uiState,
        nowMs = rememberNowSecs(loadedSecs = syncedSecs) * DateUtils.SECOND_IN_MILLIS,
        onAboutClick = onAboutClick,
        onSyncClick = viewModel::onSyncClick,
        onRemoveClick = viewModel::onRemoveClick,
        onRemoveConfirm = viewModel::onRemoveConfirm,
        onRemoveDismiss = viewModel::onRemoveDismiss,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: SettingsUiState,
    nowMs: Long,
    onAboutClick: () -> Unit,
    onSyncClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onRemoveConfirm: () -> Unit,
    onRemoveDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ListScreen { spec ->
            item {
                ListHeader(
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec)
                ) {
                    Text(stringResource(R.string.settings__title))
                }
            }
            item {
                FilledTonalButton(
                    onClick = onAboutClick,
                    icon = { RowIcon(R.drawable.ic_info) },
                    label = { Text(stringResource(R.string.about__title)) },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("WatchSettingsAbout")
                )
            }
            item {
                FilledTonalButton(
                    onClick = onSyncClick,
                    enabled = !uiState.isSyncing && !uiState.isRemoving,
                    icon = { RowIcon(R.drawable.ic_sync) },
                    label = { Text(stringResource(R.string.settings__phone_settings)) },
                    secondaryLabel = uiState.phoneSyncedAtMs?.let { syncedAtMs ->
                        {
                            Text(
                                text = syncedAgo(syncedAtMs, nowMs),
                                maxLines = 1,
                                modifier = Modifier.testTag("WatchSettingsSyncTime")
                            )
                        }
                    },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("WatchSettingsSync")
                )
            }
            if (uiState.isSyncFailed) {
                item {
                    Text(
                        text = stringResource(R.string.settings__phone_settings_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec)
                            .testTag("WatchSettingsSyncFailed")
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = onRemoveClick,
                    enabled = !uiState.isRemoving,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        iconColor = MaterialTheme.colorScheme.error,
                    ),
                    icon = { RowIcon(R.drawable.ic_delete) },
                    label = { Text(stringResource(R.string.settings__remove_wallet)) },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("WatchSettingsRemoveWallet")
                )
            }
            if (uiState.isRemoveFailed) {
                item {
                    Text(
                        text = stringResource(R.string.settings__remove_wallet_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, spec)
                            .testTag("WatchSettingsRemoveFailed")
                    )
                }
            }
        }
        if (uiState.isRemoving || uiState.isSyncing) BezelLoading()
    }
    AlertDialog(
        visible = uiState.isRemoveDialogVisible,
        onDismissRequest = onRemoveDismiss,
        title = { Text(stringResource(R.string.settings__remove_wallet_confirm_title)) },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(
                onClick = onRemoveConfirm,
                modifier = Modifier.testTag("WatchSettingsRemoveConfirm")
            )
        },
        dismissButton = {
            AlertDialogDefaults.DismissButton(
                onClick = onRemoveDismiss,
                modifier = Modifier.testTag("WatchSettingsRemoveCancel")
            )
        },
        modifier = Modifier.semantics { testTagsAsResourceId = true }
    )
}

/** How long before [nowMs] the phone's settings were imported; a time ahead of it counts as now. */
private fun syncedAgo(syncedAtMs: Long, nowMs: Long): String = DateUtils.getRelativeTimeSpanString(
    syncedAtMs.coerceAtMost(nowMs),
    nowMs,
    DateUtils.MINUTE_IN_MILLIS,
    DateUtils.FORMAT_ABBREV_RELATIVE,
).toString()

@Composable
private fun RowIcon(
    @DrawableRes id: Int,
    modifier: Modifier = Modifier,
) {
    Icon(painter = painterResource(id), contentDescription = null, modifier = modifier)
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            uiState = SettingsUiState(phoneSyncedAtMs = 0, isSyncFailed = true),
            nowMs = 120_000,
            onAboutClick = {},
            onSyncClick = {},
            onRemoveClick = {},
            onRemoveConfirm = {},
            onRemoveDismiss = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewRemoveConfirm() {
    WearPreview {
        Content(
            uiState = SettingsUiState(isRemoveDialogVisible = true),
            nowMs = 0,
            onAboutClick = {},
            onSyncClick = {},
            onRemoveClick = {},
            onRemoveConfirm = {},
            onRemoveDismiss = {},
        )
    }
}
