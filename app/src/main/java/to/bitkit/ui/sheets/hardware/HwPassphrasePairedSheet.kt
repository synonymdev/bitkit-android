package to.bitkit.ui.sheets.hardware

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import to.bitkit.R
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/**
 * Confirms the passphrase wallet Bitkit just started watching. It is the paired step of a separate
 * identity, so it carries its own funds label and can loop back for another passphrase wallet.
 */
@Composable
fun HwPassphrasePairedSheet(
    uiState: HwConnectUiState,
    modifier: Modifier = Modifier,
    onLabelChange: (String) -> Unit = {},
    onPassphrase: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    HwPairedContent(
        uiState = uiState,
        header = stringResource(R.string.hardware__passphrase_paired_header).withAccent(accentColor = Colors.Blue),
        text = stringResource(R.string.hardware__passphrase_paired_text),
        screenTag = "HardwareWalletPassphrasePairedScreen",
        onLabelChange = onLabelChange,
        onPassphrase = onPassphrase,
        onFinish = onFinish,
        modifier = modifier
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            HwPassphrasePairedSheet(
                uiState = HwConnectUiState(
                    deviceName = "Trezor Safe 3",
                    balanceSats = 5_214_983uL,
                    labelInput = "Trezor Safe 3",
                ),
                modifier = Modifier.sheetHeight()
            )
        }
    }
}
