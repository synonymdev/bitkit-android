package to.bitkit.wear.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.env.Env
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.WearPreview

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Content(
        version = Env.version,
        network = Env.network.name.lowercase(),
        modifier = modifier,
    )
}

@Composable
private fun Content(
    version: String,
    network: String,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(modifier = modifier) {
        Image(
            painter = painterResource(R.drawable.bitkit_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier.fillMaxWidth(0.5f)
        )
        Text(
            text = version,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.testTag("WatchAboutVersion")
        )
        Text(
            text = network,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("WatchAboutNetwork")
        )
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(version = "0.1.0 (1)", network = "regtest")
    }
}
