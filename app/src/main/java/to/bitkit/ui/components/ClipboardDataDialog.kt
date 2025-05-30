package to.bitkit.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import to.bitkit.R
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.theme.Shapes

@Composable
fun ClipboardDataDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = false,
        )
    ) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = Shapes.medium,
            colors = CardDefaults.cardColors(containerColor = Colors.Gray5),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
            ) {
                Title(
                    text = stringResource(R.string.other__clipboard_redirect_title),
                    color = Colors.White,
                )

                Spacer(modifier = Modifier.height(16.dp))

                BodyS(
                    text = stringResource(R.string.other__clipboard_redirect_msg),
                    color = Colors.White64,
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TertiaryButton(
                        text = stringResource(R.string.common__dialog_cancel),
                        size = ButtonSize.Small,
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    )
                    PrimaryButton(
                        text = stringResource(R.string.common__ok),
                        size = ButtonSize.Small,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        ClipboardDataDialog(
            onConfirm = {},
            onDismiss = {},
        )
    }
}
