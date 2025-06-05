package to.bitkit.ui.settings.backups

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.viewmodels.BackupContract
import to.bitkit.viewmodels.BackupViewModel

@Composable
fun BackupNavigationSheet(
    onDismiss: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.resetState()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadMnemonicData()
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BackupContract.SideEffect.NavigateToShowPassphrase -> navController.navigate(BackupRoute.ShowPassphrase)
                BackupContract.SideEffect.NavigateToConfirmMnemonic -> navController.navigate(BackupRoute.ConfirmMnemonic)
                BackupContract.SideEffect.NavigateToConfirmPassphrase -> navController.navigate(BackupRoute.ConfirmPassphrase)
                BackupContract.SideEffect.NavigateToWarning -> navController.navigate(BackupRoute.Warning)
                BackupContract.SideEffect.NavigateToSuccess -> navController.navigate(BackupRoute.Success)
                BackupContract.SideEffect.NavigateToMultipleDevices -> navController.navigate(BackupRoute.MultipleDevices)
                BackupContract.SideEffect.NavigateToMetadata -> navController.navigate(BackupRoute.Metadata)
                BackupContract.SideEffect.DismissSheet -> onDismiss()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(SheetSize.LARGE)
            .testTag("backup_navigation_sheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = BackupRoute.ShowMnemonic,
        ) {
            composableWithDefaultTransitions<BackupRoute.ShowMnemonic> {
                ShowMnemonicScreen(
                    uiState = uiState,
                    onRevealClick = viewModel::onRevealMnemonic,
                    onContinueClick = viewModel::onShowMnemonicContinue,
                )
            }
            composableWithDefaultTransitions<BackupRoute.ShowPassphrase> {
                ShowPassphraseScreen(
                    uiState = uiState,
                    onContinue = viewModel::onShowPassphraseContinue,
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.ConfirmMnemonic> {
                ConfirmMnemonicScreen(
                    uiState = uiState,
                    onContinue = viewModel::onConfirmMnemonicContinue,
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.ConfirmPassphrase> {
                ConfirmPassphraseScreen(
                    uiState = uiState,
                    onPassphraseChange = viewModel::onPassphraseInput,
                    onContinue = viewModel::onConfirmPassphraseContinue,
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.Warning> {
                WarningScreen(
                    onContinue = viewModel::onWarningContinue,
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.Success> {
                SuccessScreen(
                    onContinue = viewModel::onSuccessContinue,
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.MultipleDevices> {
                MultipleDevicesScreen(
                    onContinue = viewModel::onMultipleDevicesContinue,
                    onBack = { navController.popBackStack() },
                )
            }
            composableWithDefaultTransitions<BackupRoute.Metadata> {
                MetadataScreen(
                    uiState = uiState,
                    onDismiss = viewModel::onMetadataClose,
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
    data object ShowPassphrase

    @Serializable
    data object ConfirmMnemonic

    @Serializable
    data object ConfirmPassphrase

    @Serializable
    data object Warning

    @Serializable
    data object Success

    @Serializable
    data object MultipleDevices

    @Serializable
    data object Metadata
}
