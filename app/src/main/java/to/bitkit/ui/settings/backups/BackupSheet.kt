package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.models.BalanceState
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.WalletViewModel
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun BackupSheet(
    onDismiss: () -> Unit,
    walletViewModel: WalletViewModel,
) {
    val navController = rememberNavController()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(.775f)
    ) {
        NavHost(
            navController = navController,
            startDestination = BackupRoute.Intro,
        ) {
            composableWithDefaultTransitions<BackupRoute.Intro> {
                val balance : BalanceState by walletViewModel.balanceState.collectAsStateWithLifecycle()
                BackupIntroScreen(
                    hasFunds = balance.totalSats > 0u,
                    onClose = onDismiss,
                    onConfirm = {
                        navController.navigate(BackupRoute.Backup)
                    }
                )
            }
            composableWithDefaultTransitions<BackupRoute.Backup> {
                BackupWalletScreen(
                    navController = navController
                )
            }
        }
    }
}

object BackupRoute {
    @Serializable
    data object Intro

    @Serializable
    data object Backup
}
