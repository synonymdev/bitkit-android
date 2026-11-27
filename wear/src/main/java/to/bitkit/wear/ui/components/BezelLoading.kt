package to.bitkit.wear.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.CircularProgressIndicatorDefaults
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices

/** The gap between the screen edge and a ring drawn around it, such as the loading bezel or the hold-to-pay ring. */
val BezelPadding: Dp = CircularProgressIndicatorDefaults.FullScreenPadding

/** The stroke width of a ring drawn around the screen edge. */
val BezelStrokeWidth: Dp = 4.dp

/**
 * An indeterminate ring around the screen edge, the one loading indicator of the watch app. Content stays readable
 * inside it. It is hidden in ambient mode, which allows no animation.
 */
@Composable
fun BezelLoading(modifier: Modifier = Modifier) {
    if (isAmbient()) return
    CircularProgressIndicator(
        strokeWidth = BezelStrokeWidth,
        modifier = modifier
            .fillMaxSize()
            .padding(BezelPadding)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }
            .testTag("BezelLoading")
    )
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        BezelLoading()
    }
}
