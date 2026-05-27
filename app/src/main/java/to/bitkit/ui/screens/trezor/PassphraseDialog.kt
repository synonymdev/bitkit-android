package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
internal fun PassphraseDialog(
    onSubmit: (String) -> Unit,
    onUseTrezor: () -> Unit,
    onCancel: () -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Colors.Gray5,
        shape = MaterialTheme.shapes.medium,
        title = {
            Title(
                text = "Enter Passphrase",
                color = Colors.White,
            )
        },
        text = {
            Column {
                Caption(
                    text = "Enter the BIP39 passphrase for this wallet, or leave empty for the standard wallet:",
                    color = Colors.White80,
                )
                VerticalSpacer(16.dp)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    placeholder = {
                        Footnote("Passphrase", color = Colors.White32)
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Colors.White,
                        unfocusedTextColor = Colors.White,
                        focusedBorderColor = Colors.Brand,
                        unfocusedBorderColor = Colors.White32,
                        cursorColor = Colors.Brand,
                    ),
                )
                VerticalSpacer(12.dp)
                TertiaryButton(
                    text = "Enter on Trezor instead",
                    onClick = onUseTrezor,
                    size = ButtonSize.Small,
                    fullWidth = false,
                )
            }
        },
        confirmButton = {
            TertiaryButton(
                text = "Submit",
                onClick = { onSubmit(passphrase) },
                enabled = true,
                size = ButtonSize.Small,
                fullWidth = false,
            )
        },
        dismissButton = {
            TertiaryButton(
                text = "Cancel",
                onClick = onCancel,
                size = ButtonSize.Small,
                fullWidth = false,
            )
        },
    )
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewPassphraseDialog() {
    AppThemeSurface {
        Box(Modifier.fillMaxSize()) {
            PassphraseDialog(onSubmit = {}, onUseTrezor = {}, onCancel = {})
        }
    }
}
