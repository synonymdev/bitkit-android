package to.bitkit.wear.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview

@Composable
fun SettingsScreen(
    onAboutClick: () -> Unit,
    onWalletRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnWalletRemove by rememberUpdatedState(onWalletRemove)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect {
            when (it) {
                SettingsEffect.WalletRemoved -> currentOnWalletRemove()
            }
        }
    }
    Content(
        uiState = uiState,
        onFiatPrimaryChange = viewModel::onFiatPrimaryChange,
        onAboutClick = onAboutClick,
        onRemoveClick = viewModel::onRemoveClick,
        onRemoveConfirm = viewModel::onRemoveConfirm,
        onRemoveDismiss = viewModel::onRemoveDismiss,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: SettingsUiState,
    onFiatPrimaryChange: (Boolean) -> Unit,
    onAboutClick: () -> Unit,
    onRemoveClick: () -> Unit,
    onRemoveConfirm: () -> Unit,
    onRemoveDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListScreen(modifier = modifier) { spec ->
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec)
            ) {
                Text(stringResource(R.string.settings__title))
            }
        }
        item {
            SwitchButton(
                checked = uiState.isFiatPrimary,
                onCheckedChange = onFiatPrimaryChange,
                label = { Text(stringResource(R.string.settings__primary_fiat)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchSettingsFiatPrimary")
            )
        }
        item {
            FilledTonalButton(
                onClick = onAboutClick,
                label = { Text(stringResource(R.string.about__title)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchSettingsAbout")
            )
        }
        item {
            Button(
                onClick = onRemoveClick,
                enabled = !uiState.isRemoving,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
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
    AlertDialog(
        visible = uiState.isRemoveDialogVisible,
        onDismissRequest = onRemoveDismiss,
        title = { Text(stringResource(R.string.settings__remove_wallet_confirm_title)) },
        text = { Text(stringResource(R.string.settings__remove_wallet_confirm_text)) },
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
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            uiState = SettingsUiState(isFiatPrimary = true),
            onFiatPrimaryChange = {},
            onAboutClick = {},
            onRemoveClick = {},
            onRemoveConfirm = {},
            onRemoveDismiss = {},
        )
    }
}
