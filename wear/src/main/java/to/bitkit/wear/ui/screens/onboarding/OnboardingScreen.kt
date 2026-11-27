package to.bitkit.wear.ui.screens.onboarding

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import to.bitkit.wear.R
import to.bitkit.wear.ui.components.CenteredScreen
import to.bitkit.wear.ui.components.WearPreview
import to.bitkit.wear.ui.components.WearPreviewDevices

/** The Bitkit mark and the one way in for now: restoring a recovery phrase. */
@Composable
fun OnboardingScreen(
    onRestoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScreen(
        edgeButton = {
            EdgeButton(
                onClick = onRestoreClick,
                buttonSize = EdgeButtonSize.Medium,
                modifier = Modifier.testTag("WatchOnboardingRestore")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_restore),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Text(stringResource(R.string.onboarding__restore))
            }
        },
        modifier = modifier
    ) {
        Image(
            painter = painterResource(R.drawable.bitkit_logo),
            contentDescription = stringResource(R.string.app_name),
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .testTag("WatchOnboardingLogo")
        )
    }
}

@WearPreviewDevices
@Composable
private fun Preview() {
    WearPreview {
        OnboardingScreen(onRestoreClick = {})
    }
}
