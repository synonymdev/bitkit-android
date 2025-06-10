package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ConfirmPassphraseScreen(
    uiState: BackupContract.UiState,
    onPassphraseChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    ConfirmPassphraseContent(
        enteredPassphrase = uiState.enteredPassphrase,
        isValid = uiState.enteredPassphrase == uiState.bip39Passphrase,
        onPassphraseChange = onPassphraseChange,
        onContinue = {
            keyboardController?.hide()
            onContinue()
        },
        onBack = onBack,
    )
}

@Composable
private fun ConfirmPassphraseContent(
    enteredPassphrase: String,
    isValid: Boolean,
    onPassphraseChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .navigationBarsPadding()
            .imePadding()
            .testTag("backup_confirm_passphrase_screen")
    ) {
        SheetTopBar(stringResource(R.string.security__pass_confirm), onBack = onBack)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            BodyM(
                text = stringResource(R.string.security__pass_confirm_text),
                color = Colors.White64,
            )

            Spacer(modifier = Modifier.height(32.dp))

            TextInput(
                placeholder = stringResource(R.string.security__pass).replaceFirstChar { it.uppercase() },
                value = enteredPassphrase,
                onValueChange = onPassphraseChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("backup_passphrase_input")
            )

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                enabled = isValid,
                modifier = Modifier.testTag("backup_confirm_passphrase_continue_button")
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ConfirmPassphraseContent(
            enteredPassphrase = "",
            isValid = false,
            onPassphraseChange = {},
            onContinue = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        ConfirmPassphraseContent(
            enteredPassphrase = "test123",
            isValid = true,
            onPassphraseChange = {},
            onContinue = {},
            onBack = {},
        )
    }
}
