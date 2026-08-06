package to.bitkit.ui.sheets.hardware

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetPreview
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HW_ILLUSTRATION_SIZE_RATIO
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/**
 * Optional step of the connect flow: the passphrase that unlocks a hidden wallet on the paired
 * device. Bitkit binds it to a fresh Trezor session to read that wallet's accounts and never
 * stores it, so it is asked for again whenever the session has to be rebuilt.
 */
@Composable
fun HwPassphraseSheet(
    uiState: HwConnectUiState,
    modifier: Modifier = Modifier,
    onPassphraseChange: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Content(
        uiState = uiState,
        onPassphraseChange = onPassphraseChange,
        onBack = onBack,
        onContinue = onContinue,
        modifier = modifier
    )
}

@Composable
private fun Content(
    uiState: HwConnectUiState,
    modifier: Modifier = Modifier,
    onPassphraseChange: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onContinue: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .imePadding()
            .testTag("HardwareWalletPassphraseScreen")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__passphrase_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(stringResource(R.string.hardware__passphrase_header).withAccent(accentColor = Colors.Blue))
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.hardware__passphrase_text), color = Colors.White64)
            VerticalSpacer(32.dp)
            TextInput(
                value = uiState.passphraseInput,
                onValueChange = onPassphraseChange,
                singleLine = true,
                // A passphrase is case- and character-exact: never let the keyboard alter it.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("HardwareWalletPassphraseInput")
            )
        }
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
        ) {
            Image(
                painter = painterResource(R.drawable.shield),
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(maxWidth * HW_ILLUSTRATION_SIZE_RATIO)
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            SecondaryButton(
                text = stringResource(R.string.common__back),
                onClick = onBack,
                enabled = !uiState.isSubmittingPassphrase,
                modifier = Modifier
                    .weight(1f)
                    .testTag("HardwareWalletPassphraseBack")
            )
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                enabled = uiState.passphraseInput.isNotEmpty(),
                isLoading = uiState.isSubmittingPassphrase,
                modifier = Modifier
                    .weight(1f)
                    .testTag("HardwareWalletPassphraseContinue")
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
                uiState = HwConnectUiState(passphraseInput = "satoshirulestheworld"),
                modifier = Modifier.sheetHeight()
            )
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewSubmitting() {
    AppThemeSurface {
        BottomSheetPreview {
            Content(
                uiState = HwConnectUiState(
                    passphraseInput = "satoshirulestheworld",
                    isSubmittingPassphrase = true,
                ),
                modifier = Modifier.sheetHeight()
            )
        }
    }
}
