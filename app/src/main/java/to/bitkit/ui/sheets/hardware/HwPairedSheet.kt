package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceView
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

// Coin illustration proportions taken from the Figma "Hardware Funds Paired" frame (375 wide,
// 256-wide bottom Visual) and the coin_stack_3 asset's intrinsic 756x926 size.
private const val COINS_WIDTH_RATIO = 256f / 375f
private const val COINS_ASPECT_RATIO = 756f / 926f

@Composable
fun HwPairedSheet(
    uiState: HwConnectUiState,
    modifier: Modifier = Modifier,
    onLabelChange: (String) -> Unit = {},
    onFinish: () -> Unit = {},
) {
    Content(
        uiState = uiState,
        onLabelChange = onLabelChange,
        onFinish = onFinish,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: HwConnectUiState,
    modifier: Modifier = Modifier,
    onLabelChange: (String) -> Unit = {},
    onFinish: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .imePadding()
            .testTag("HwPairedScreen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__paired_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(stringResource(R.string.hardware__paired_header).withAccent(accentColor = Colors.Blue))
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.hardware__paired_text), color = Colors.White64)
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
                    .testTag("HwPairedLabelField")
            )
        }
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
                    .width(maxWidth * COINS_WIDTH_RATIO)
                    .aspectRatio(COINS_ASPECT_RATIO)
            )
            PrimaryButton(
                text = stringResource(R.string.hardware__paired_finish),
                onClick = onFinish,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 32.dp, end = 32.dp, bottom = 16.dp)
                    .testTag("HwPairedFinish")
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
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
