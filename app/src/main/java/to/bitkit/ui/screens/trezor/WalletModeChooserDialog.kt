package to.bitkit.ui.screens.trezor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.Title
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

/**
 * Asks where the user wants to enter the passphrase for a hidden wallet —
 * on the phone (host) or on the Trezor's own screen. Only shown when the
 * device reports it can accept on-device passphrase entry.
 */
@Composable
internal fun WalletModeChooserDialog(
    onHost: () -> Unit,
    onTrezor: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Colors.Gray5,
        shape = MaterialTheme.shapes.medium,
        title = {
            Title(text = "Passphrase Entry", color = Colors.White)
        },
        text = {
            Column {
                Caption(
                    text = "Where do you want to enter the passphrase for your hidden wallet?",
                    color = Colors.White80,
                )
                VerticalSpacer(16.dp)
                PrimaryButton(
                    text = "On this phone",
                    onClick = onHost,
                    size = ButtonSize.Small,
                )
                VerticalSpacer(8.dp)
                PrimaryButton(
                    text = "On the Trezor",
                    onClick = onTrezor,
                    size = ButtonSize.Small,
                )
            }
        },
        dismissButton = {
            TertiaryButton(
                text = "Cancel",
                onClick = onCancel,
                size = ButtonSize.Small,
                fullWidth = false,
            )
        },
        confirmButton = {},
    )
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewWalletModeChooserDialog() {
    AppThemeSurface {
        Box(Modifier.fillMaxSize()) {
            WalletModeChooserDialog(onHost = {}, onTrezor = {}, onCancel = {})
        }
    }
}
