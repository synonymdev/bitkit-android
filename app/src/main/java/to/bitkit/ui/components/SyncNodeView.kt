package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.screens.transfer.components.TransferAnimationView
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun SyncNodeView(modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        FillHeight()

        TransferAnimationView(
            largeCircleRes = R.drawable.ln_sync_large,
            smallCircleRes = R.drawable.ln_sync_small,
            contentRes = R.drawable.lightning,
            rotateContent = false
        )

        FillHeight()

        Display(
            text = stringResource(R.string.lightning__sync_connecting)
                .withAccent(accentColor = Colors.Purple),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )

        VerticalSpacer(8.dp)

        BodyM(
            text = stringResource(R.string.lightning__wait_text_top),
            color = Colors.White64,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        )

        VerticalSpacer(45.dp)

        GradientCircularProgressIndicator(
            modifier = Modifier.size(24.dp)
        )

        VerticalSpacer(8.dp)
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SyncNodeView(
            modifier = Modifier
                .fillMaxSize()
                .gradientBackground()
        )
    }
}
