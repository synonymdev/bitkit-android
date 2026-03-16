package to.bitkit.ui.scaffold

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.window.DialogProperties
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.Title
import to.bitkit.ui.shared.modifiers.rememberDebouncedClick
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

@Composable
fun AppAlertDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = stringResource(R.string.common__ok),
    dismissText: String = stringResource(R.string.common__dialog_cancel),
    onDismissRequest: () -> Unit = onDismiss,
    properties: DialogProperties = DialogProperties(
        dismissOnClickOutside = false,
        dismissOnBackPress = false,
    ),
) {
    AppAlertDialog(
        title = title,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier,
        confirmText = confirmText,
        dismissText = dismissText,
        onDismissRequest = onDismissRequest,
        properties = properties,
        textContent = { BodyM(text = text, color = Colors.White64) },
    )
}

@Composable
fun AppAlertDialog(
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    confirmText: String = stringResource(R.string.common__ok),
    dismissText: String = stringResource(R.string.common__dialog_cancel),
    onDismissRequest: () -> Unit = onDismiss,
    properties: DialogProperties = DialogProperties(
        dismissOnClickOutside = false,
        dismissOnBackPress = false,
    ),
    textContent: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = rememberDebouncedClick(onClick = onConfirm),
                modifier = Modifier.testTag("DialogConfirm")
            ) {
                BodyMSB(text = confirmText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = rememberDebouncedClick(onClick = onDismiss),
                modifier = Modifier.testTag("DialogCancel")
            ) {
                BodyMSB(text = dismissText, color = Colors.White64)
            }
        },
        title = { Title(text = title) },
        text = textContent,
        shape = MaterialTheme.shapes.medium,
        properties = properties,
        containerColor = Colors.Gray5,
        modifier = modifier.semantics { testTagsAsResourceId = true },
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
