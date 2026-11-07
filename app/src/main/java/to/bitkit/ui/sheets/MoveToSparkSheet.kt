package to.bitkit.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Tells channel holders that Blocktank closes its channels and that moving the balance to Spark costs least. */
@Composable
fun MoveToSparkSheet(onLater: () -> Unit, onMove: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.sheetHeight().navigationBarsPadding().testTag("MoveToSparkIntro")
    ) {
        SheetTopBar(stringResource(R.string.lightning__move_to_spark__nav_title))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            // The illustration shrinks on small screens so the copy below it stays whole.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(R.drawable.lightning),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.sizeIn(maxWidth = 256.dp, maxHeight = 256.dp)
                )
            }
            VerticalSpacer(32.dp)
            Display(
                stringResource(R.string.lightning__move_to_spark__intro_title).withAccent(accentColor = Colors.Purple)
            )
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.lightning__move_to_spark__intro_text), color = Colors.White64)
        }
        VerticalSpacer(32.dp)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            SecondaryButton(
                stringResource(R.string.common__later),
                onClick = onLater,
                testTag = "MoveToSparkIntroLater",
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                stringResource(R.string.lightning__move_to_spark__move_now),
                onClick = onMove,
                modifier = Modifier.weight(1f).testTag("MoveToSparkIntroMove")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface { BottomSheetPreview { MoveToSparkSheet({}, {}) } }
}
