package to.bitkit.ui.screens.wallets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import to.bitkit.R
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppSwitchDefaults
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors

/**
 * Confirms removing a paired hardware wallet, offering to carry its name and tags in the backup so
 * re-pairing the device restores them. Shared by the wallet screen and the hardware wallet settings.
 */
@Composable
fun RemoveHwWalletDialog(
    walletName: String,
    keepBackupData: Boolean,
    onKeepBackupDataChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AppAlertDialog(
        title = stringResource(R.string.hardware__remove_dialog_title, walletName),
        confirmText = stringResource(R.string.common__remove),
        dismissText = stringResource(R.string.common__cancel),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        modifier = modifier.testTag("RemoveHwWalletDialog")
    ) {
        Column {
            BodyM(text = stringResource(R.string.hardware__remove_dialog_text), color = Colors.White64)
            VerticalSpacer(16.dp)
            KeepBackupDataRow(
                keepBackupData = keepBackupData,
                onKeepBackupDataChange = onKeepBackupDataChange,
            )
        }
    }
}

@Composable
private fun KeepBackupDataRow(
    keepBackupData: Boolean,
    onKeepBackupDataChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickableAlpha { onKeepBackupDataChange(!keepBackupData) }
            .testTag("HwRemoveKeepBackupToggle")
    ) {
        BodyMSB(
            text = stringResource(R.string.hardware__remove_dialog_keep),
            modifier = Modifier.weight(1f)
        )
        HorizontalSpacer(16.dp)
        Switch(
            checked = keepBackupData,
            onCheckedChange = null, // handled by parent
            colors = AppSwitchDefaults.colors,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        RemoveHwWalletDialog(
            walletName = "Trezor Safe 3",
            keepBackupData = true,
            onKeepBackupDataChange = {},
            onConfirm = {},
            onDismiss = {},
        )
    }
}
