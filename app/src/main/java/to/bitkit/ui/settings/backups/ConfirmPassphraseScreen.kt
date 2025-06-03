package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun ConfirmPassphraseScreen(
    bip39Passphrase: String,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    var enteredPassphrase by remember { mutableStateOf("") }
    val keyboardController = LocalSoftwareKeyboardController.current

    DisposableEffect(Unit) {
        onDispose {
            enteredPassphrase = "" // Clear passphrase from memory
        }
    }

    ConfirmPassphraseContent(
        enteredPassphrase = enteredPassphrase,
        originalPassphrase = bip39Passphrase,
        onPassphraseChange = { enteredPassphrase = it },
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
    originalPassphrase: String,
    onPassphraseChange: (String) -> Unit,
    onContinue: () -> Unit,
    onBack: () -> Unit,
) {
    val isValid = enteredPassphrase == originalPassphrase
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .gradientBackground()
            .padding(horizontal = 32.dp)
            .imePadding()
    ) {
        SheetTopBar(
            titleText = stringResource(R.string.security__pass_confirm),
            onBack = onBack,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            BodyM(
                text = stringResource(R.string.security__pass_confirm_text),
                color = Colors.White64,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(32.dp))

            OutlinedTextField(
                value = enteredPassphrase,
                onValueChange = onPassphraseChange,
                placeholder = {
                    BodyM(
                        text = stringResource(R.string.security__pass).replaceFirstChar { it.uppercase() },
                        color = Colors.White32
                    )
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Done,
                    autoCorrect = false
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (isValid) {
                            keyboardController?.hide()
                            onContinue()
                        }
                    }
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Colors.White,
                    unfocusedTextColor = Colors.White,
                    focusedBorderColor = Colors.White32,
                    unfocusedBorderColor = Colors.White16,
                    focusedContainerColor = Colors.White10,
                    unfocusedContainerColor = Colors.White10,
                    cursorColor = Colors.Brand,
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.weight(1f))

            PrimaryButton(
                text = stringResource(R.string.common__continue),
                onClick = onContinue,
                enabled = isValid,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ConfirmPassphraseContent(
            enteredPassphrase = "",
            originalPassphrase = "test123",
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
            originalPassphrase = "test123",
            onPassphraseChange = {},
            onContinue = {},
            onBack = {},
        )
    }
}
