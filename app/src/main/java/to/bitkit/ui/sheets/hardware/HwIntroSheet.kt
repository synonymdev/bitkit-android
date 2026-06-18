package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HwDeviceIllustrations
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun HwIntroSheet(
    modifier: Modifier = Modifier,
    onContinue: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Content(
        onContinue = onContinue,
        onCancel = onCancel,
        modifier = modifier
    )
}

@Composable
private fun Content(
    modifier: Modifier = Modifier,
    onContinue: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("hw_intro_screen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__intro_title))
        HwDeviceIllustrations(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(stringResource(R.string.hardware__intro_header).withAccent(accentColor = Colors.Blue))
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.hardware__intro_text), color = Colors.White64)
            VerticalSpacer(32.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.common__cancel),
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = onContinue,
                    modifier = Modifier.weight(1f)
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewIntro() {
    AppThemeSurface {
        BottomSheetPreview {
            Content()
        }
    }
}
