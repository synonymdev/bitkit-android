package to.bitkit.wear.ui.screens.start

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.BezelLoading
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices

/**
 * Shown instead of a first screen when the wallet storage could not be recovered at launch, as when a wallet removal
 * that did not complete cannot be finished. Nothing opens the wallet until a retry succeeds.
 */
@Composable
fun StartErrorScreen(
    isLoading: Boolean,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        overlay = { if (isLoading) BezelLoading() },
        modifier = modifier
    ) {
        Text(
            text = stringResource(R.string.home__error),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("WatchStartError")
        )
        FilledTonalIconButton(
            onClick = onRetryClick,
            enabled = !isLoading,
            shapes = IconButtonDefaults.animatedShapes(),
            modifier = Modifier.testTag("WatchStartRetry")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_refresh),
                contentDescription = stringResource(R.string.common__retry),
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        StartErrorScreen(isLoading = false, onRetryClick = {})
    }
}
