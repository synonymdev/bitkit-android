package to.bitkit.wear.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.ListScreen
import to.bitkit.wear.ui.components.WearPreview

@Composable
fun OnboardingScreen(
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListScreen(modifier = modifier) { spec ->
        item {
            Image(
                painter = painterResource(R.drawable.bitkit_logo),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .transformedHeight(this, spec)
                    .testTag("WatchOnboardingLogo")
            )
        }
        item {
            Text(
                text = stringResource(R.string.onboarding__text),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
            )
        }
        item {
            FilledTonalButton(
                onClick = {},
                enabled = false,
                label = { Text(stringResource(R.string.onboarding__set_up_phone)) },
                secondaryLabel = { Text(stringResource(R.string.common__coming_soon)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchOnboardingPhone")
            )
        }
        item {
            Button(
                onClick = onRestoreClick,
                label = { Text(stringResource(R.string.onboarding__restore)) },
                transformation = SurfaceTransformation(spec),
                modifier = Modifier
                    .fillMaxWidth()
                    .transformedHeight(this, spec)
                    .testTag("WatchOnboardingRestore")
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        OnboardingScreen(onRestoreClick = {})
    }
}
