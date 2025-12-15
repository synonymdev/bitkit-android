package to.bitkit.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import kotlinx.serialization.Serializable
import to.bitkit.ui.LocalBalances
import to.bitkit.ui.components.Sheet
import to.bitkit.ui.components.SheetSize
import to.bitkit.ui.settings.backups.BackupContract
import to.bitkit.ui.settings.backups.BackupIntroScreen
import to.bitkit.ui.settings.backups.BackupNavSheetViewModel
import to.bitkit.ui.settings.backups.ConfirmMnemonicScreen
import to.bitkit.ui.settings.backups.ConfirmPassphraseScreen
import to.bitkit.ui.settings.backups.MetadataScreen
import to.bitkit.ui.settings.backups.MultipleDevicesScreen
import to.bitkit.ui.settings.backups.ShowMnemonicScreen
import to.bitkit.ui.settings.backups.ShowPassphraseScreen
import to.bitkit.ui.settings.backups.SuccessScreen
import to.bitkit.ui.settings.backups.WarningScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.utils.composableWithDefaultTransitions

@Composable
fun BackupSheet(
    sheet: Sheet.Backup,
    onDismiss: () -> Unit,
    viewModel: BackupNavSheetViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(Unit) {
        viewModel.loadMnemonicData()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.resetState()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                BackupContract.SideEffect.NavigateToShowPassphrase -> navController.navigate(BackupRoute.ShowPassphrase)
                BackupContract.SideEffect.NavigateToConfirmMnemonic -> navController.navigate(
                    BackupRoute.ConfirmMnemonic
                )

                BackupContract.SideEffect.NavigateToConfirmPassphrase -> navController.navigate(
                    BackupRoute.ConfirmPassphrase
                )

                BackupContract.SideEffect.NavigateToWarning -> navController.navigate(BackupRoute.Warning)
                BackupContract.SideEffect.NavigateToSuccess -> navController.navigate(BackupRoute.Success)
                BackupContract.SideEffect.NavigateToMultipleDevices -> navController.navigate(
                    BackupRoute.MultipleDevices
                )

                BackupContract.SideEffect.NavigateToMetadata -> navController.navigate(BackupRoute.Metadata)
                BackupContract.SideEffect.DismissSheet -> currentOnDismiss()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(SheetSize.MEDIUM)
            .testTag("backup_navigation_sheet")
    ) {
        NavHost(
            navController = navController,
            startDestination = sheet.route,
        ) {
            composableWithDefaultTransitions<BackupRoute.Intro> {
                BackupIntroScreen(
                    hasFunds = LocalBalances.current.totalSats > 0u,
                    onClose = currentOnDismiss,
                    onConfirm = { navController.navigate(BackupRoute.ShowMnemonic) },
                )
            }
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

sealed interface BackupRoute {
    @Serializable
    data object Intro : BackupRoute

    @Serializable
    data object ShowMnemonic : BackupRoute

    @Serializable
    data object ShowPassphrase : BackupRoute

    @Serializable
    data object ConfirmMnemonic : BackupRoute

    @Serializable
    data object ConfirmPassphrase : BackupRoute

    @Serializable
    data object Warning : BackupRoute

    @Serializable
    data object Success : BackupRoute

    @Serializable
    data object MultipleDevices : BackupRoute

    @Serializable
    data object Metadata : BackupRoute
}
