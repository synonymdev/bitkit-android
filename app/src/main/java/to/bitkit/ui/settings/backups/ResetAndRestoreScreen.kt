package to.bitkit.ui.settings.backups

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import to.bitkit.R
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BottomSheetType
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.walletViewModel

object ResetAndRestoreTestTags {
    const val SCREEN = "restore_screen"
    const val BACKUP_BUTTON = "restore_backup_button"
    const val RESET_BUTTON = "restore_reset_button"
    const val RESET_DIALOG = "restore_reset_button"
}

@Composable
fun ResetAndRestoreScreen(
    navController: NavController,
) {
    val app = appViewModel ?: return
    val wallet = walletViewModel ?: return
    var showDialog by remember { mutableStateOf(false) }

    Content(
        showConfirmDialog = showDialog,
        onClickBackup = { app.showSheet(BottomSheetType.BackupNavigation) },
        onClickReset = { showDialog = true },
        onResetConfirm = { wallet.wipeWallet() },
        onResetDismiss = { showDialog = false },
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
    )
}

@Composable
private fun Content(
    showConfirmDialog: Boolean,
    onClickBackup: () -> Unit,
    onClickReset: () -> Unit,
    onResetConfirm: () -> Unit,
    onResetDismiss: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.security__reset_title),
            onBackClick = onBack,
            actions = { CloseNavIcon(onClick = onClose) },
        )
        Spacer(Modifier.height(32.dp))

        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .testTag(ResetAndRestoreTestTags.SCREEN)
        ) {
            BodyM(
                text = stringResource(R.string.security__reset_text),
                color = Colors.White64,
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Image(
                    painter = painterResource(R.drawable.restore),
                    contentDescription = null,
                    modifier = Modifier.size(256.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.security__reset_button_backup),
                    onClick = onClickBackup,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ResetAndRestoreTestTags.BACKUP_BUTTON)
                )
                PrimaryButton(
                    text = stringResource(R.string.security__reset_button_reset),
                    onClick = onClickReset,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ResetAndRestoreTestTags.RESET_BUTTON)
                )
            }

            Spacer(Modifier.height(16.dp))
        }

        if (showConfirmDialog) {
            AppAlertDialog(
                title = stringResource(R.string.security__reset_dialog_title),
                text = stringResource(R.string.security__reset_dialog_desc),
                confirmText = stringResource(R.string.security__reset_confirm),
                onConfirm = onResetConfirm,
                onDismiss = onResetDismiss,
                modifier = Modifier.testTag(ResetAndRestoreTestTags.RESET_DIALOG)
            )
        }
    }
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            showConfirmDialog = false,
            onClickBackup = {},
            onClickReset = {},
            onResetConfirm = {},
            onResetDismiss = {},
            onBack = {},
            onClose = {},
        )
    }
}

@Preview
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            showConfirmDialog = true,
            onClickBackup = {},
            onClickReset = {},
            onResetConfirm = {},
            onResetDismiss = {},
            onBack = {},
            onClose = {},
        )
    }
}
