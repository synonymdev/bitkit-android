package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPinSheet(
    onDismiss: () -> Unit,
    onResetClick: () -> Unit,
) {
    BottomSheet(onDismissRequest = onDismiss) {
        Content(
            onBackClick = onDismiss,
            onResetClick = {
                onDismiss()
                onResetClick()
            },
        )
    }
}

@Composable
private fun Content(
    onBackClick: (() -> Unit)? = null,
    onResetClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .sheetHeight(isModal = true)
            .gradientBackground()
            .padding(horizontal = 16.dp)
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__pin_forgot_title),
            onBack = onBackClick,
        )
        VerticalSpacer(16.dp)

        BodyM(
            text = stringResource(R.string.security__pin_forgot_text),
            color = Colors.White64,
            modifier = Modifier.testTag("ForgotPIN")
        )

        FillHeight()
        Image(
            painter = painterResource(R.drawable.restore),
            contentDescription = null,
            modifier = Modifier.width(256.dp)
        )
        FillHeight()

        PrimaryButton(
            text = stringResource(R.string.security__pin_forgot_reset),
            onClick = onResetClick,
        )

        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                onBackClick = {},
                onResetClick = {},
            )
        }
    }
}
