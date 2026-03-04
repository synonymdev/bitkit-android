package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun SweepPromptSheet(
    onSweep: () -> Unit,
    onCancel: () -> Unit,
) {
    Content(
        onSweep = onSweep,
        onCancel = onCancel,
    )
}

@Composable
private fun Content(
    modifier: Modifier = Modifier,
    onSweep: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .sheetHeight()
            .gradientBackground()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("SweepPromptSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.sweep__nav_title))

        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Image(
                painter = painterResource(R.drawable.coin_stack_x),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(311.dp)
            )
        }

        VerticalSpacer(16.dp)

        Display(text = stringResource(R.string.sweep__prompt_title).withAccent())

        VerticalSpacer(8.dp)

        BodyM(
            text = stringResource(R.string.sweep__prompt_description),
            color = Colors.White64,
            modifier = Modifier.fillMaxWidth()
        )

        VerticalSpacer(32.dp)

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .testTag("CancelButton")
            )
            PrimaryButton(
                text = stringResource(R.string.sweep__prompt_sweep),
                onClick = onSweep,
                modifier = Modifier
                    .weight(1f)
                    .testTag("SweepButton")
            )
        }

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
