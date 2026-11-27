package to.bitkit.wear.ui.screens.restore

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.WearPreview

/** Tells the wearer that the typed words fail the BIP39 checksum and sends them back through the words. */
@Composable
fun RestoreChecksumScreen(
    onReviewClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        edgeButton = {
            EdgeButton(
                onClick = onReviewClick,
                buttonSize = EdgeButtonSize.Medium,
                modifier = Modifier.testTag("RestoreChecksumReview")
            ) {
                Text(stringResource(R.string.restore__checksum_review))
            }
        },
        modifier = modifier,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_error),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(36.dp)
        )
        Text(
            text = stringResource(R.string.restore__checksum_title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("RestoreChecksumError")
        )
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        RestoreChecksumScreen(onReviewClick = {})
    }
}
