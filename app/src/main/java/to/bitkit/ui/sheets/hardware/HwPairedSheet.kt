package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HW_ILLUSTRATION_SIZE_RATIO
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/** Width reuses the shared 256-wide Figma Visual ratio; aspect is the coin_stack_3 asset's intrinsic 756x926. */
private const val COINS_ASPECT_RATIO = 756f / 926f

@Composable
fun HwPairedSheet(
    uiState: HwConnectUiState,
    modifier: Modifier = Modifier,
    onLabelChange: (String) -> Unit = {},
    onPassphrase: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    HwPairedContent(
        uiState = uiState,
        header = stringResource(R.string.hardware__paired_header).withAccent(accentColor = Colors.Blue),
        text = stringResource(R.string.hardware__paired_text),
        screenTag = "HardwareWalletPairedScreen",
        onLabelChange = onLabelChange,
        onPassphrase = onPassphrase,
        onFinish = onFinish,
        modifier = modifier
    )
}

/**
 * Paired step shared by the standard wallet and the passphrase wallet found afterwards: both
 * confirm the watched balance and its Bitkit-side label, and both can add another passphrase
 * wallet from the same device before finishing.
 */
@Composable
internal fun HwPairedContent(
    uiState: HwConnectUiState,
    header: AnnotatedString,
    text: String,
    screenTag: String,
    modifier: Modifier = Modifier,
    onLabelChange: (String) -> Unit = {},
    onPassphrase: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val hazeState = rememberHazeState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .imePadding()
            .testTag(screenTag)
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__paired_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(header)
            VerticalSpacer(8.dp)
            BodyM(text, color = Colors.White64)
            VerticalSpacer(32.dp)
            Row(modifier = Modifier.fillMaxWidth()) {
                WalletBalanceView(
                    title = uiState.deviceName,
                    sats = uiState.balanceSats.toLong(),
                    icon = painterResource(R.drawable.ic_btc_circle_blue),
                )
            }
            VerticalSpacer(32.dp)
            Caption13Up(stringResource(R.string.hardware__paired_label), color = Colors.White64)
            VerticalSpacer(8.dp)
            TextInput(
                value = uiState.labelInput,
                onValueChange = onLabelChange,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("HardwareWalletLabelInput")
            )
        }
        // The buttons sit over the coins, so the illustration is the haze source and must stay a
        // sibling of the blurred button: haze cannot blur an ancestor.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            Image(
                painter = painterResource(R.drawable.coin_stack_3),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .requiredWidth(maxWidth * HW_ILLUSTRATION_SIZE_RATIO)
                    .aspectRatio(COINS_ASPECT_RATIO)
                    .hazeSource(hazeState)
            )
            HwPairedButtons(
                hazeState = hazeState,
                onPassphrase = onPassphrase,
                onFinish = onFinish,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            )
        }
    }
}

@Composable
private fun HwPairedButtons(
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onPassphrase: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        SecondaryButton(
            text = stringResource(R.string.hardware__passphrase_button),
            onClick = onPassphrase,
            hazeState = hazeState,
            modifier = Modifier
                .weight(1f)
                .testTag("HardwareWalletPairedPassphrase")
        )
        PrimaryButton(
            text = stringResource(R.string.hardware__paired_finish),
            onClick = onFinish,
            modifier = Modifier
                .weight(1f)
                .testTag("HardwareWalletPairedFinish")
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            HwPairedSheet(
                uiState = HwConnectUiState(
                    deviceName = "Trezor Safe 3",
                    balanceSats = 10_562_411uL,
                    labelInput = "Trezor Safe 3",
                ),
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        BottomSheetPreview {
            HwPairedSheet(
                uiState = HwConnectUiState(deviceName = "Trezor Safe 3"),
                modifier = Modifier.sheetHeight()
            )
        }
    }
}
