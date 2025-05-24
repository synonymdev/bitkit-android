package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.ui.utils.composableWithDefaultTransitions

@Composable
fun BackupSheet(
    onDismiss: () -> Unit,
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
                BackupIntroScreen(
                    hasFunds = true,
                    onClose = onDismiss,
                    onConfirm = {
                        navController.navigate(BackupRoute.Backup)
                        //TODO update hasSeen
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
