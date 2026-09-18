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
fun QuickPayIntroSheet(
    onLater: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetIntro(
        navTitle = stringResource(R.string.settings__quickpay__nav_title),
        title = stringResource(R.string.settings__quickpay__intro__title).withAccent(accentColor = Colors.Green),
        description = AnnotatedString(stringResource(R.string.settings__quickpay__sheet__description)),
        image = R.drawable.fast_forward,
        continueText = stringResource(R.string.common__learn_more),
        onContinue = onContinue,
        cancelText = stringResource(R.string.common__later),
        onCancel = onLater,
        testTag = "QuickpayIntro",
        cancelTestTag = "QuickpayIntro-later",
        continueTestTag = "QuickpayIntro-learnMore",
        modifier = modifier
            .sheetHeight(isModal = true)
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            QuickPayIntroSheet(
                onLater = {},
                onContinue = {},
            )
        }
    }
}
