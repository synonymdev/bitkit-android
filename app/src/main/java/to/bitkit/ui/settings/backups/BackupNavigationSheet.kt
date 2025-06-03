package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.utils.composableWithDefaultTransitions

@Composable
fun BackupNavigationSheet(
    onDismiss: () -> Unit,
) {
    val navController = rememberNavController()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(SheetSize.MEDIUM)
    ) {
        NavHost(
            navController = navController,
            startDestination = BackupRoute.ShowMnemonic,
        ) {
            composableWithDefaultTransitions<BackupRoute.ShowMnemonic> {
                ShowMnemonicScreen(
                    onContinue = { seed, bip39Passphrase ->
                        if (bip39Passphrase.isNotEmpty()) {
                            navController.navigate(BackupRoute.ShowPassphrase(seed, bip39Passphrase))
                        } else {
                            navController.navigate(BackupRoute.ConfirmMnemonic(seed, bip39Passphrase))
                        }
                    },
                    onDismiss = onDismiss,
                )
            }
            composableWithDefaultTransitions<BackupRoute.ShowPassphrase> { backStackEntry ->
                val route = backStackEntry.toRoute<BackupRoute.ShowPassphrase>()
                ShowPassphraseScreen(
                    seed = route.seed,
                    bip39Passphrase = route.bip39Passphrase,
                    onContinue = {
                        navController.navigate(BackupRoute.ConfirmMnemonic(route.seed, route.bip39Passphrase))
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.ConfirmMnemonic> { backStackEntry ->
                val route = backStackEntry.toRoute<BackupRoute.ConfirmMnemonic>()
                ConfirmMnemonicScreen(
                    seed = route.seed,
                    bip39Passphrase = route.bip39Passphrase,
                    onContinue = {
                        if (route.bip39Passphrase.isNotEmpty()) {
                            navController.navigate(BackupRoute.ConfirmPassphrase(route.bip39Passphrase))
                        } else {
                            navController.navigate(BackupRoute.Warning)
                        }
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.ConfirmPassphrase> { backStackEntry ->
                val route = backStackEntry.toRoute<BackupRoute.ConfirmPassphrase>()
                ConfirmPassphraseScreen(
                    bip39Passphrase = route.bip39Passphrase,
                    onContinue = {
                        navController.navigate(BackupRoute.Warning)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.Warning> {
                WarningScreen(
                    onContinue = {
                        navController.navigate(BackupRoute.Success)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.Success> {
                SuccessScreen(
                    onContinue = {
                        navController.navigate(BackupRoute.MultipleDevices)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.MultipleDevices> {
                MultipleDevicesScreen(
                    onContinue = {
                        navController.navigate(BackupRoute.Metadata)
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.Metadata> {
                MetadataScreen(
                    onDismiss = onDismiss,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

object BackupRoute {
    @Serializable
    data object ShowMnemonic

    @Serializable
    data class ShowPassphrase(val seed: List<String>, val bip39Passphrase: String)

    @Serializable
    data class ConfirmMnemonic(val seed: List<String>, val bip39Passphrase: String)

    @Serializable
    data class ConfirmPassphrase(val bip39Passphrase: String)

    @Serializable
    data object Warning

    @Serializable
    data object Success

    @Serializable
    data object MultipleDevices

    @Serializable
    data object Metadata
}
