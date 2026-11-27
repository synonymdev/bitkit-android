package to.bitkit.wear.ui.screens.unsupported

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import to.bitkit.wear.R
import to.bitkit.wear.env.Env
import to.bitkit.wear.env.WalletNetwork
import to.bitkit.wear.ui.components.MessageScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices

/**
 * Shown on a network or build where the watch wallet is not available: testnet, which Spark does not have, and
 * mainnet while it is off, each with its one-line reason.
 */
@Composable
fun UnsupportedScreen(modifier: Modifier = Modifier) {
    Content(isMainnet = Env.network == WalletNetwork.BITCOIN, modifier = modifier)
}

@Composable
private fun Content(
    isMainnet: Boolean,
    modifier: Modifier = Modifier,
) {
    val message = if (isMainnet) R.string.unsupported__text_mainnet else R.string.unsupported__text
    MessageScreen(
        title = stringResource(R.string.unsupported__title),
        message = stringResource(message),
        testTag = "WatchUnsupported",
        modifier = modifier
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(isMainnet = false)
    }
}

@WearPreviewDevices
@Composable
private fun PreviewMainnet() {
    WearPreview {
        Content(isMainnet = true)
    }
}
