package to.bitkit.wear.ui.screens.settings

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

@Composable
fun SettingsScreen(
    onAboutClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Content(
        uiState = uiState,
        onAboutClick = onAboutClick,
        onRemoveClick = viewModel::onRemoveClick,
        onRemoveConfirm = viewModel::onRemoveConfirm,
        onRemoveDismiss = viewModel::onRemoveDismiss,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: SettingsUiState,
    onAboutClick: () -> Unit,
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
        if (uiState.isRemoving) BezelLoading()
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
            uiState = SettingsUiState(),
            onAboutClick = {},
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
            onAboutClick = {},
            onRemoveClick = {},
            onRemoveConfirm = {},
            onRemoveDismiss = {},
        )
    }
}
