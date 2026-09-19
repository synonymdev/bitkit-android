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
fun BackgroundPaymentsIntroSheet(
    onLater: () -> Unit,
    onEnable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetIntro(
        navTitle = stringResource(R.string.settings__bg__title),
        title = stringResource(R.string.settings__bg__intro_title).withAccent(accentColor = Colors.Purple),
        description = AnnotatedString(stringResource(R.string.settings__bg__intro_desc)),
        image = R.drawable.bell_figure,
        continueText = stringResource(R.string.settings__bg__intro_button),
        onContinue = onEnable,
        cancelText = stringResource(R.string.common__later),
        onCancel = onLater,
        testTag = "BackgroundPaymentsIntro",
        cancelTestTag = "BackgroundPaymentsIntro-later",
        continueTestTag = "BackgroundPaymentsIntro-enable",
        modifier = modifier
            .sheetHeight(isModal = true)
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            BackgroundPaymentsIntroSheet(
                onLater = {},
                onEnable = {},
            )
        }
    }
}
