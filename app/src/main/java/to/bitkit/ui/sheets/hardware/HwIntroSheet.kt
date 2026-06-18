package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

// Proportions from Figma v61 frame
private const val INTRO_IMAGE_SIZE_RATIO = 256f / 375f
private const val INTRO_TREZOR_BLEED_RATIO = 84f / 375f
private const val INTRO_LEDGER_BLEED_RATIO = 53f / 375f
private const val INTRO_IMAGE_STAGGER_RATIO = 12f / 375f

@Composable
fun HwIntroSheet(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    Content(
        onDismiss = onDismiss,
        modifier = modifier
    )
}

@Composable
private fun Content(
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("hw_intro_screen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__intro_title))
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val imageSize = maxWidth * INTRO_IMAGE_SIZE_RATIO
            val staggerY = maxWidth * INTRO_IMAGE_STAGGER_RATIO
            TrezorImage(imageSize, staggerY)
            LedgerImage(imageSize, staggerY, modifier = Modifier.blur(16.dp, BlurredEdgeTreatment.Unbounded))
        }
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
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                PrimaryButton(
                    text = stringResource(R.string.common__continue),
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.weight(1f)
                )
            }
            VerticalSpacer(16.dp)
        }
    }
}

@Composable
private fun BoxWithConstraintsScope.TrezorImage(
    imageSize: Dp,
    staggerY: Dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.trezor),
        contentDescription = null,
        modifier = modifier
            .size(imageSize)
            .align(Alignment.CenterStart)
            .offset(x = -maxWidth * INTRO_TREZOR_BLEED_RATIO, y = staggerY)
    )
}

@Composable
private fun BoxWithConstraintsScope.LedgerImage(
    imageSize: Dp,
    staggerY: Dp,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(R.drawable.ledger),
        contentDescription = null,
        modifier = modifier
            .size(imageSize)
            .align(Alignment.CenterEnd)
            .offset(x = maxWidth * INTRO_LEDGER_BLEED_RATIO, y = -staggerY)
    )
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
