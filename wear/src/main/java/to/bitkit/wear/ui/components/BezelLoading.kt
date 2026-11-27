package to.bitkit.wear.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults

/** The gap between the screen edge and a ring drawn around it, such as the loading bezel or the hold-to-pay ring. */
val BezelPadding: Dp = CircularProgressIndicatorDefaults.FullScreenPadding

/** The stroke width of a ring drawn around the screen edge. */
val BezelStrokeWidth: Dp = 4.dp

/**
 * An indeterminate ring around the screen edge, the one loading indicator of the watch app. Content stays readable
 * inside it. It is hidden in ambient mode, which allows no animation.
 *
 * Accessibility services see only the content of a screen that no node above it covers, so the full-screen ring has
 * no semantics of its own. Its one progress node is a stroke-sized spot at the top of the ring, which leaves the
 * content beneath reachable by TalkBack and `android layout`.
 */
@Composable
fun BezelLoading(modifier: Modifier = Modifier) {
    if (isAmbient()) return
    Box(modifier = modifier.fillMaxSize()) {
        CircularProgressIndicator(
            strokeWidth = BezelStrokeWidth,
            modifier = Modifier
                .fillMaxSize()
                .padding(BezelPadding)
                .clearAndSetSemantics {}
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = BezelPadding)
                .size(BezelStrokeWidth)
                .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
                .testTag("BezelLoading")
        )
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        BezelLoading()
    }
}
