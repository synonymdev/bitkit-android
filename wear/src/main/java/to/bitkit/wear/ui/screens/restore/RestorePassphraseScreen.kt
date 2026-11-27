package to.bitkit.wear.ui.screens.restore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.BlockScreenshots
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.SecretTextField
import to.bitkit.wear.ui.components.WearPreview

/**
 * Offers to add the wallet's optional BIP39 passphrase, skipped by default, then saves the wallet. The screen blocks
 * screenshots.
 */
@Composable
fun RestorePassphraseScreen(
    viewModel: RestoreViewModel,
    modifier: Modifier = Modifier,
) {
    BlockScreenshots()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Content(
        uiState = uiState,
        onSkipClick = viewModel::onPassphraseSkip,
        onAddClick = viewModel::onPassphraseAdd,
        onPassphraseChange = viewModel::onPassphraseChange,
        onConfirmClick = viewModel::onPassphraseConfirm,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: RestoreUiState,
    onSkipClick: () -> Unit,
    onAddClick: () -> Unit,
    onPassphraseChange: (String) -> Unit,
    onConfirmClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.isPassphraseEditing) {
        Editing(
            uiState = uiState,
            onPassphraseChange = onPassphraseChange,
            onConfirmClick = onConfirmClick,
            modifier = modifier,
        )
        return
    }
    CenteredScreen(
        edgeButton = {
            EdgeButton(
                onClick = onSkipClick,
                enabled = !uiState.isSaving,
                buttonSize = EdgeButtonSize.Medium,
                modifier = Modifier.testTag("RestorePassphraseSkip")
            ) {
                Text(stringResource(R.string.restore__passphrase_skip))
            }
        },
        overlay = { if (uiState.isSaving) Saving() },
        modifier = modifier,
    ) {
        Text(
            text = stringResource(R.string.restore__passphrase_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("RestorePassphraseTitle")
        )
        FilledTonalIconButton(
            onClick = onAddClick,
            enabled = !uiState.isSaving,
            shapes = IconButtonDefaults.animatedShapes(),
            modifier = Modifier.testTag("RestorePassphraseAdd")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_key),
                contentDescription = stringResource(R.string.restore__passphrase_add),
            )
        }
        if (uiState.isSaveFailed) SaveError()
    }
}

@Composable
private fun Editing(
    uiState: RestoreUiState,
    onPassphraseChange: (String) -> Unit,
    onConfirmClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Box(modifier = modifier.fillMaxSize()) {
        ListScreen(
            edgeButton = {
                EdgeButton(
                    onClick = onConfirmClick,
                    enabled = !uiState.isSaving,
                    buttonSize = EdgeButtonSize.Small,
                    modifier = Modifier.testTag("RestorePassphraseConfirm")
                ) {
                    Text(stringResource(R.string.restore__passphrase_confirm))
                }
            },
        ) { spec ->
            item {
                ListHeader(
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier.transformedHeight(this, spec)
                ) {
                    Text(
                        text = stringResource(R.string.restore__passphrase_placeholder),
                        modifier = Modifier.testTag("RestorePassphraseTitle")
                    )
                }
            }
            item {
                SecretTextField(
                    value = uiState.passphrase,
                    onValueChange = onPassphraseChange,
                    placeholder = stringResource(R.string.restore__passphrase_placeholder),
                    onDone = { keyboard?.hide() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("RestorePassphraseInput")
                )
            }
            if (uiState.isSaveFailed) {
                item { SaveError(modifier = Modifier.transformedHeight(this, spec)) }
            }
        }
        if (uiState.isSaving) Saving()
    }
}

@Composable
private fun Saving(modifier: Modifier = Modifier) {
    Box(modifier = modifier.testTag("RestoreSaving")) {
        BezelLoading()
    }
}

@Composable
private fun SaveError(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.restore__error_failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .testTag("RestoreError")
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            uiState = RestoreUiState(),
            onSkipClick = {},
            onAddClick = {},
            onPassphraseChange = {},
            onConfirmClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewSaving() {
    WearPreview {
        Content(
            uiState = RestoreUiState(isSaving = true),
            onSkipClick = {},
            onAddClick = {},
            onPassphraseChange = {},
            onConfirmClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewEditing() {
    WearPreview {
        Content(
            uiState = RestoreUiState(isPassphraseEditing = true),
            onSkipClick = {},
            onAddClick = {},
            onPassphraseChange = {},
            onConfirmClick = {},
        )
    }
}
