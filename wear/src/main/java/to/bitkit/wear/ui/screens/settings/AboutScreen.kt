package to.bitkit.wear.ui.screens.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.env.Env
import to.bitkit.wear.ui.components.ListScreen
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
    ListScreen(modifier = modifier) { spec ->
        item {
            ListHeader(
                transformation = SurfaceTransformation(spec),
                modifier = Modifier.transformedHeight(this, spec)
            ) {
                Text(stringResource(R.string.about__title))
            }
        }
        item {
            FilledTonalButton(
                onClick = {},
                label = { Text(stringResource(R.string.about__version)) },
                secondaryLabel = { Text(version) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchAboutVersion")
            )
        }
        item {
            FilledTonalButton(
                onClick = {},
                label = { Text(stringResource(R.string.about__network)) },
                secondaryLabel = { Text(network) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchAboutNetwork")
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        Content(version = "0.1.0 (1)", network = "regtest")
    }
}
