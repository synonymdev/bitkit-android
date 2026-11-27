package to.bitkit.wear.ui.screens.restore

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.MessageScreen
import to.bitkit.wear.ui.components.WearPreview

/** Placeholder until restoring from a recovery phrase lands. */
@Composable
fun RestoreScreen(modifier: Modifier = Modifier) {
    MessageScreen(
        title = stringResource(R.string.restore__title),
        message = stringResource(R.string.common__coming_soon),
        testTag = "WatchRestore",
        modifier = modifier,
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        RestoreScreen()
    }
}
