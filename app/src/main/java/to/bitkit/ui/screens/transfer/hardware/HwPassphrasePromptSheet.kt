package to.bitkit.ui.screens.transfer.hardware

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.HW_ILLUSTRATION_SIZE_RATIO
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.effects.BlockScreenshots
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent

/**
 * Asks for the passphrase of the hidden wallet a transfer signs from. Bitkit never stores it, so
 * it is needed again whenever the Trezor session that held it is gone. What is typed stays local
 * to this sheet and is handed straight to the device session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HwPassphrasePromptSheet(
    isVerifying: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val dismissKeyboard = {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    fun closeSheet() {
        scope.launch {
            dismissKeyboard()
            sheetState.hide()
            onDismiss()
        }
    }

    BottomSheet(
        onDismissRequest = { closeSheet() },
        sheetState = sheetState,
        modifier = Modifier.imePadding()
    ) {
        Content(
            isVerifying = isVerifying,
            onSubmit = {
                dismissKeyboard()
                onSubmit(it)
            },
            onCancel = { closeSheet() },
            modifier = Modifier.sheetHeight(SheetSize.LARGE, isModal = true)
        )
    }
}

@Composable
private fun Content(
    isVerifying: Boolean,
    modifier: Modifier = Modifier,
    onSubmit: (String) -> Unit = {},
    onCancel: () -> Unit = {},
) {
    BlockScreenshots()

    var passphrase by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .imePadding()
            .testTag("HwTransferPassphraseSheet")
    ) {
        SheetTopBar(titleText = stringResource(R.string.hardware__passphrase_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
        ) {
            Display(stringResource(R.string.hardware__passphrase_header).withAccent(accentColor = Colors.Blue))
            VerticalSpacer(8.dp)
            BodyM(stringResource(R.string.hardware__passphrase_sign_text), color = Colors.White64)
            VerticalSpacer(32.dp)
            TextInput(
                value = passphrase,
                onValueChange = { passphrase = it },
                singleLine = true,
                // A passphrase is case- and character-exact: never let the keyboard alter it.
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    capitalization = KeyboardCapitalization.None,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .testTag("HwTransferPassphraseInput")
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
                text = stringResource(R.string.common__cancel),
                onClick = onCancel,
                enabled = !isVerifying,
                modifier = Modifier
                    .weight(1f)
                    .testTag("HwTransferPassphraseCancel")
            )
            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = { onSubmit(passphrase) },
                enabled = passphrase.isNotEmpty(),
                isLoading = isVerifying,
                modifier = Modifier
                    .weight(1f)
                    .testTag("HwTransferPassphraseContinue")
            )
        }
        VerticalSpacer(16.dp)
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(isVerifying = false)
    }
}
