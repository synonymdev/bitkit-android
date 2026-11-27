package to.bitkit.wear.ui.screens.restore

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview

/** Tells the wearer that the typed words fail the BIP39 checksum and sends them back through the words. */
@Composable
fun RestoreChecksumScreen(
    onReviewClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListScreen(modifier = modifier) { spec ->
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec)
            ) {
                Text(
                    text = stringResource(R.string.restore__checksum_title),
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("RestoreChecksumError")
                )
            }
        }
        item {
            Button(
                onClick = onReviewClick,
                label = { Text(stringResource(R.string.restore__checksum_review)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("RestoreChecksumReview")
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        RestoreChecksumScreen(onReviewClick = {})
    }
}
