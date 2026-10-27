package to.bitkit.ui.screens.wallets.receive

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.sheets.TransferUnavailableContent
import to.bitkit.ui.theme.AppThemeSurface

@Composable
fun TransferUnavailableScreen(
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TransferUnavailableContent(
        navTitle = stringResource(R.string.lightning__transfer_unavailable__nav_title),
        description = stringResource(R.string.lightning__transfer_unavailable__text),
        onContinue = onContinue,
        onBack = onBack,
        modifier = modifier,
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            TransferUnavailableScreen(
                onBack = {},
                onContinue = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
