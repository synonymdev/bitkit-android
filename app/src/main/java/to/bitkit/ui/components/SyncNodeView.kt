package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

@Composable
fun SyncNodeView(modifier: Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        VerticalSpacer(32.dp)

        BodyM(
            text = stringResource(R.string.lightning__wait_text_top),
            color = Colors.White64,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        FillHeight()

        TransferAnimationView(
            largeCircleRes = R.drawable.ln_sync_large,
            smallCircleRes = R.drawable.ln_sync_small,
            contentRes = R.drawable.lightning,
            rotateContent = false
        )

        FillHeight()

        BodySSB(text = stringResource(R.string.lightning__wait_text_bottom), color = Colors.White32)

        VerticalSpacer(32.dp)
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
                .padding(horizontal = 16.dp)
        )
    }
}
