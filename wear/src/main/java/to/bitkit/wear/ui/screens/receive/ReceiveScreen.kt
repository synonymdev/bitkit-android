package to.bitkit.wear.ui.screens.receive

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices

/** The size of the receive button: the page's one large control. */
private val BUTTON_SIZE = 88.dp

/** The size of the QR icon inside the button. */
private val ICON_SIZE = 44.dp

/** The receive page of the home pager: one large round button that opens the receive QR. */
@Composable
fun ReceiveScreen(
    onQrClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        FilledIconButton(
            onClick = onQrClick,
            shapes = IconButtonDefaults.animatedShapes(),
            modifier = Modifier
                .size(BUTTON_SIZE)
                .testTag("WatchReceiveOpen")
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_qr_code),
                contentDescription = stringResource(R.string.receive__title),
                modifier = Modifier.size(ICON_SIZE)
            )
        }
        Text(
            text = stringResource(R.string.receive__title),
            style = MaterialTheme.typography.titleSmall,
            // The button says it already; read once.
            modifier = Modifier.clearAndSetSemantics {}
        )
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        ReceiveScreen(onQrClick = {})
    }
}
