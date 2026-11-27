package to.bitkit.wear.ui.screens.comingsoon

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.MessageScreen
import to.bitkit.wear.ui.components.WearPreview

/** Placeholder for a feature that is not on the watch yet. */
@Composable
fun ComingSoonScreen(
    title: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    MessageScreen(
        title = title,
        message = stringResource(R.string.common__coming_soon),
        testTag = testTag,
        modifier = modifier,
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        ComingSoonScreen(title = stringResource(R.string.home__send), testTag = "WatchSend")
    }
}
