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
fun LocationBlockScreen(
    onBackPressed: () -> Unit,
    navigateAdvancedSetup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TransferUnavailableContent(
        navTitle = stringResource(R.string.wallet__receive_bitcoin),
        description = stringResource(R.string.lightning__transfer_unavailable__text_receive),
        onContinue = navigateAdvancedSetup,
        onBack = onBackPressed,
        modifier = modifier,
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            LocationBlockScreen(
                onBackPressed = {},
                navigateAdvancedSetup = {},
                modifier = Modifier.sheetHeight(),
            )
        }
    }
}
