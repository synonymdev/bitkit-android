package to.bitkit.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.scaffold.SheetTopBar
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
        SheetTopBar(stringResource(R.string.title_send))
        Spacer(Modifier.height(32.dp))

        Spacer(modifier = Modifier.weight(1f))

        TransferAnimationView(
            largeCircleRes = R.drawable.ln_sync_large,
            smallCircleRes = R.drawable.ln_sync_small,
            contentRes = R.drawable.lightning,
            rotateContent = false
        )

        Spacer(modifier = Modifier.weight(1f))

        BodySSB(text = stringResource(R.string.lightning__wait_text_bottom), color = Colors.White32)

        Spacer(modifier = Modifier.height(18.dp))
    }
}


@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        SyncNodeView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .gradientBackground()
        )
    }
}
