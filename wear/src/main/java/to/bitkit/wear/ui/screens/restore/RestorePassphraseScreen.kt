package to.bitkit.wear.ui.screens.restore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.SecretTextField
import to.bitkit.wear.ui.components.WearPreview

/** Offers to add the wallet's optional BIP39 passphrase, skipped by default, then saves the wallet. */
@Composable
fun RestorePassphraseScreen(
    viewModel: RestoreViewModel,
    modifier: Modifier = Modifier,
) {
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
    val keyboard = LocalSoftwareKeyboardController.current
    ListScreen(modifier = modifier) { spec ->
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec)
            ) {
                Text(
                    text = stringResource(R.string.restore__passphrase_title),
                    modifier = Modifier.testTag("RestorePassphraseTitle")
                )
            }
        }
        if (uiState.isSaving) {
            item {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("RestoreSaving")
                    )
                }
            }
            return@ListScreen
        }
        if (uiState.isSaveFailed) {
            item {
                Text(
                    text = stringResource(R.string.restore__error_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("RestoreError")
                )
            }
        }
        if (uiState.isPassphraseEditing) {
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
            item {
                Button(
                    onClick = onConfirmClick,
                    label = { Text(stringResource(R.string.restore__passphrase_confirm)) },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("RestorePassphraseConfirm")
                )
            }
            return@ListScreen
        }
        item {
            Button(
                onClick = onSkipClick,
                label = { Text(stringResource(R.string.restore__passphrase_skip)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("RestorePassphraseSkip")
            )
        }
        item {
            FilledTonalButton(
                onClick = onAddClick,
                label = { Text(stringResource(R.string.restore__passphrase_add)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("RestorePassphraseAdd")
            )
        }
    }
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
