package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
internal fun PinEntryDialog(
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Colors.Gray5,
        shape = MaterialTheme.shapes.medium,
        title = {
            Title(
                text = "Enter PIN",
                color = Colors.White,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Caption(
                    text = "Use the layout shown on your Trezor to enter your PIN:",
                    color = Colors.White80,
                )
                VerticalSpacer(12.dp)
                Text(
                    text = "\u25CF".repeat(pin.length).ifEmpty { " " },
                    color = Colors.White,
                    fontSize = 24.sp,
                    textAlign = TextAlign.Center,
                    letterSpacing = 8.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacer(12.dp)
                PinMatrix(
                    onDigit = { digit -> if (pin.length < 9) pin += digit },
                    modifier = Modifier.fillMaxWidth(),
                )
                VerticalSpacer(8.dp)
                TertiaryButton(
                    text = "Delete",
                    onClick = { if (pin.isNotEmpty()) pin = pin.dropLast(1) },
                    enabled = pin.isNotEmpty(),
                    size = ButtonSize.Small,
                    fullWidth = false,
                )
            }
        },
        confirmButton = {
            TertiaryButton(
                text = "Submit",
                onClick = { onSubmit(pin) },
                enabled = pin.isNotEmpty(),
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

@Composable
private fun PinMatrix(
    onDigit: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = listOf(
        listOf("7", "8", "9"),
        listOf("4", "5", "6"),
        listOf("1", "2", "3"),
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    Button(
                        onClick = { onDigit(digit) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Colors.White16,
                            contentColor = Colors.White,
                        ),
                        modifier = Modifier.size(56.dp),
                    ) {
                        Text(
                            text = "\u25CF",
                            fontSize = 20.sp,
                        )
                    }
                }
            }
        }
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewPinEntryDialog() {
    AppThemeSurface {
        Box(Modifier.fillMaxSize()) {
            PinEntryDialog(onSubmit = {}, onCancel = {})
        }
    }
}
