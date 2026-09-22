package to.bitkit.ui.sheets

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.SheetIntro
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppTextStyles
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun HighBalanceWarningSheet(
    understoodClick: () -> Unit,
    learnMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SheetIntro(
        navTitle = stringResource(R.string.other__high_balance__nav_title),
        title = stringResource(R.string.other__high_balance__title).withAccent(accentColor = Colors.Yellow),
        description = stringResource(R.string.other__high_balance__text).withAccent(
            defaultColor = Colors.White64,
            accentStyle = AppTextStyles.Subtitle.merge(color = Colors.White).toSpanStyle(),
        ),
        image = R.drawable.exclamation_mark,
        continueText = stringResource(R.string.other__high_balance__continue),
        onContinue = understoodClick,
        cancelText = stringResource(R.string.other__high_balance__cancel),
        onCancel = learnMoreClick,
        testTag = "HighBalanceSheet",
        modifier = modifier.sheetHeight(isModal = true)
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            HighBalanceWarningSheet(
                understoodClick = {},
                learnMoreClick = {},
            )
        }
    }
}
