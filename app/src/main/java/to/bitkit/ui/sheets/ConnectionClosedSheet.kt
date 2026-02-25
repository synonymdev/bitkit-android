package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ConnectionClosedSheet(
    onDismiss: () -> Unit,
) {
    Content(onDismiss = onDismiss)
}

@Composable
private fun Content(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .sheetHeight()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("ConnectionClosedSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.lightning__connection_closed__title))

        BodyM(
            text = stringResource(R.string.lightning__connection_closed__description),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Image(
                painter = painterResource(R.drawable.switch_box),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(256.dp)
            )
        }

        PrimaryButton(
            text = stringResource(R.string.common__ok),
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("ConnectionClosedButton")
        )

        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content()
        }
    }
}
