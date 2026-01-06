package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.settings.backgroundPayments.BackgroundPaymentsIntroContent
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun BackgroundPaymentsIntroSheet(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .sheetHeight(isModal = true)
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("background_payments_intro_sheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.settings__bg__title))
        BackgroundPaymentsIntroContent(onContinue = onContinue)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Column {
            BottomSheetPreview {
                BackgroundPaymentsIntroSheet(
                    onContinue = {},
                )
            }
        }
    }
}
