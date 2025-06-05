package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Title
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun AppAlertDialog(
    title: String,
    text: String,
    confirmButtonText: String = stringResource(R.string.common__ok),
    dismissButtonText: String = stringResource(R.string.common__dialog_cancel),
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onDismissRequest: () -> Unit = onDismiss,
    properties: DialogProperties = DialogProperties(
        dismissOnClickOutside = false,
        dismissOnBackPress = false,
    ),
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                BodyMSB(text = confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                BodyMSB(text = dismissButtonText, color = Colors.White64)
            }
        },
        title = { Title(text = title) },
        text = { BodyM(text = text, color = Colors.White64) },
        shape = MaterialTheme.shapes.medium,
        properties = properties,
        containerColor = Colors.Gray5,
    )
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Box(Modifier.fillMaxSize()) {
            AppAlertDialog(
                title = "Are you sure?",
                text = "You're about do something critically cool. This action cannot be undone.",
                onConfirm = {},
                onDismiss = {},
            )
        }
    }
}
