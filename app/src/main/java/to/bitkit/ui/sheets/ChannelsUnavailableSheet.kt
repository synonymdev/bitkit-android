package to.bitkit.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.SheetIntro
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun ChannelsUnavailableSheet(
    onContinue: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetIntro(
        navTitle = stringResource(R.string.lightning__channels_unavailable__nav_title),
        title = stringResource(R.string.lightning__channels_unavailable__title).withAccent(accentColor = Colors.Purple),
        description = AnnotatedString(stringResource(R.string.lightning__channels_unavailable__text)),
        image = R.drawable.lightning,
        continueText = stringResource(R.string.common__continue),
        onContinue = onContinue,
        cancelText = stringResource(R.string.common__cancel),
        onCancel = onCancel,
        testTag = "ChannelsUnavailableSheet",
        modifier = modifier.sheetHeight(isModal = true)
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            ChannelsUnavailableSheet(
                onContinue = {},
                onCancel = {},
            )
        }
    }
}
