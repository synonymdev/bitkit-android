package to.bitkit.ui.settings.backups

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

@Composable
fun BackupWalletScreen(
    navController: NavController,
) {
    BackupNavigationSheet(
        onDismiss = { navController.popBackStack() },
    )
}
