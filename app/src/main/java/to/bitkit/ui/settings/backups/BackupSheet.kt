package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.models.BalanceState
import to.bitkit.ui.components.SheetSize
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun BackupSheet(
    onDismiss: () -> Unit,
    onBackupClick: () -> Unit,
    walletViewModel: WalletViewModel,
) {
    val balance : BalanceState by walletViewModel.balanceState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(SheetSize.MEDIUM)
    ) {
        BackupIntroScreen(
            hasFunds = balance.totalSats > 0u,
            onClose = onDismiss,
            onConfirm = onBackupClick,
        )
    }
}
