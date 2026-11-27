package to.bitkit.wear.ui.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.isAmbient

@Composable
fun HomeScreen(
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onWalletRemove: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.isRemoved) {
        if (uiState.isRemoved) onWalletRemove()
    }
    Content(
        uiState = uiState,
        isAmbient = isAmbient(),
        onSendClick = onSendClick,
        onReceiveClick = onReceiveClick,
        onSettingsClick = onSettingsClick,
        onRetryClick = viewModel::onRetryClick,
        modifier = modifier,
    )
}

@Composable
private fun Content(
    uiState: HomeUiState,
    isAmbient: Boolean,
    onSendClick: () -> Unit,
    onReceiveClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListScreen(
        edgeButton = if (isAmbient) {
            null
        } else {
            {
                EdgeButton(
                    onClick = onReceiveClick,
                    buttonSize = EdgeButtonSize.Medium,
                    modifier = Modifier.testTag("WatchHomeReceive")
                ) {
                    Text(stringResource(R.string.home__receive))
                }
            }
        },
        modifier = modifier,
    ) { spec ->
        item {
            Balance(
                uiState = uiState,
                isAmbient = isAmbient,
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
            )
        }
        if (isAmbient) return@ListScreen
        if (uiState.isError) {
            item {
                FilledTonalButton(
                    onClick = onRetryClick,
                    label = { Text(stringResource(R.string.common__retry)) },
                    transformation = SurfaceTransformation(spec),
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, spec)
                        .testTag("WatchHomeRetry")
                )
            }
        }
        item {
            Button(
                onClick = onSendClick,
                label = { Text(stringResource(R.string.home__send)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchHomeSend")
            )
        }
        item {
            FilledTonalButton(
                onClick = onSettingsClick,
                label = { Text(stringResource(R.string.home__settings)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchHomeSettings")
            )
        }
    }
}

@Composable
private fun Balance(
    uiState: HomeUiState,
    isAmbient: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(vertical = 8.dp)
    ) {
        when {
            isAmbient -> {
                val hidden = stringResource(R.string.home__balance_hidden)
                Text(
                    text = "• • •",
                    style = MaterialTheme.typography.numeralSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .semantics { contentDescription = hidden }
                        .testTag("WatchHomeBalanceHidden")
                )
            }
            uiState.isError -> Text(
                text = stringResource(R.string.home__error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("WatchHomeError")
            )
            uiState.primaryAmount == null -> CircularProgressIndicator(
                modifier = Modifier
                    .size(32.dp)
                    .testTag("WatchHomeLoading")
            )
            else -> {
                Text(
                    text = uiState.primaryAmount,
                    style = MaterialTheme.typography.numeralSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("WatchHomeBalance")
                )
                uiState.secondaryAmount?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("WatchHomeBalanceSecondary")
                    )
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(
            uiState = HomeUiState(primaryAmount = "₿ 125 000", secondaryAmount = "$121.38"),
            isAmbient = false,
            onSendClick = {},
            onReceiveClick = {},
            onSettingsClick = {},
            onRetryClick = {},
        )
    }
}

@WearPreviewDevices
@Composable
private fun PreviewAmbient() {
    WearPreview {
        Content(
            uiState = HomeUiState(primaryAmount = "₿ 125 000", secondaryAmount = "$121.38"),
            isAmbient = true,
            onSendClick = {},
            onReceiveClick = {},
            onSettingsClick = {},
            onRetryClick = {},
        )
    }
}
