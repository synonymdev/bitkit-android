package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.Colors

@Composable
internal fun PairingCodeDialog(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Colors.Gray5,
        title = {
            Text(
                text = "Enter Pairing Code",
                color = Colors.White,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column {
                Text(
                    text = "Enter the 6-digit code shown on your Trezor device:",
                    color = Colors.White80,
                    fontSize = 14.sp,
                )
                VerticalSpacer(16.dp)
                OutlinedTextField(
                    value = code,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() } && newValue.length <= 6) {
                            code = newValue
                        }
                    },
                    placeholder = {
                        Text("000000", color = Colors.White32)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Colors.White,
                        unfocusedTextColor = Colors.White,
                        focusedBorderColor = Colors.Brand,
                        unfocusedBorderColor = Colors.White32,
                        cursorColor = Colors.Brand,
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 24.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        letterSpacing = 8.sp,
                    ),
                )
            }
        },
        confirmButton = {
            TertiaryButton(
                text = "Submit",
                onClick = { onSubmit(code) },
                enabled = code.length == 6,
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
