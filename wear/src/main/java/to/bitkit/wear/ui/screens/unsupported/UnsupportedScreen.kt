package to.bitkit.wear.ui.screens.unsupported

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.MessageScreen
import to.bitkit.wear.ui.components.WearPreview

/** Shown on a network or build where Spark is not available, such as testnet. */
@Composable
fun UnsupportedScreen(modifier: Modifier = Modifier) {
    MessageScreen(
        title = stringResource(R.string.unsupported__title),
        message = stringResource(R.string.unsupported__text),
        testTag = "WatchUnsupported",
        modifier = modifier,
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        UnsupportedScreen()
    }
}
