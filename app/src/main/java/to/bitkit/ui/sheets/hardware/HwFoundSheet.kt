package to.bitkit.ui.sheets.hardware

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

@Composable
fun HwFoundSheet(
    deviceModel: String,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false,
    errorMessage: String? = null,
    onConnect: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Content(
        deviceModel = deviceModel,
        isConnecting = isConnecting,
        errorMessage = errorMessage,
        onConnect = onConnect,
        onCancel = onCancel,
        modifier = modifier
    )
}

@Composable
private fun Content(
    deviceModel: String,
    modifier: Modifier = Modifier,
    isConnecting: Boolean = false,
    errorMessage: String? = null,
    onConnect: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .testTag("HwFoundScreen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__found_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(stringResource(R.string.hardware__found_header).withAccent(accentColor = Colors.Blue))
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.hardware__found_text, deviceModel), color = Colors.White64)
            AnimatedVisibility(visible = errorMessage != null) {
                Column {
                    VerticalSpacer(16.dp)
                    BodyS(
                        text = errorMessage.orEmpty(),
                        color = Colors.Red,
                        modifier = Modifier.testTag("HwFoundError")
                    )
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Image(
                painter = painterResource(R.drawable.trezor),
                contentDescription = null,
                modifier = Modifier.size(256.dp)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                modifier = Modifier.weight(1f)
            )
            PrimaryButton(
                text = stringResource(R.string.common__connect),
                onClick = onConnect,
                isLoading = isConnecting,
                enabled = !isConnecting,
                modifier = Modifier.weight(1f)
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                deviceModel = "Trezor Safe 3",
                modifier = Modifier.sheetHeight()
            )
        }
    }
}
