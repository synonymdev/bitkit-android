package to.bitkit.ui.screens.recovery

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import to.bitkit.R
import to.bitkit.ui.components.AuthCheckView
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SettingsViewModel

@Composable
fun RecoveryModeScreen(
    appViewModel: AppViewModel,
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    recoveryViewModel: RecoveryViewModel = hiltViewModel(),
    onNavigateToSeed: () -> Unit = {},
) {
    val uiState by recoveryViewModel.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            recoveryViewModel.disableRecoveryMode()
        }
    }

    Box(
        modifier = modifier,
    ) {
        Content(
            uiState = uiState,
            walletExists = uiState.walletExists,
            onExportLogs = recoveryViewModel::onExportLogs,
            onShowSeed = {
                if (uiState.isPinEnabled) {
                    recoveryViewModel.setAuthAction(PendingAuthAction.ShowSeed)
                } else {
                    onNavigateToSeed()
                }
            },
            onContactSupport = recoveryViewModel::onContactSupport,
            onWipeApp = {
                if (uiState.isPinEnabled) {
                    recoveryViewModel.setAuthAction(PendingAuthAction.WipeApp)
                } else {
                    recoveryViewModel.showWipeConfirmation()
                }
            },
            onWipeConfirm = {
                recoveryViewModel.hideWipeConfirmation()
                recoveryViewModel.wipeWallet()
            },
            onWipeCancel = recoveryViewModel::hideWipeConfirmation,
        )

        AnimatedVisibility(
            visible = uiState.authAction != PendingAuthAction.None,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            AuthCheckView(
                showLogoOnPin = true,
                appViewModel = appViewModel,
                settingsViewModel = settingsViewModel,
                onSuccess = {
                    when (uiState.authAction) {
                        PendingAuthAction.None -> Unit
                        PendingAuthAction.ShowSeed -> onNavigateToSeed()
                        PendingAuthAction.WipeApp -> recoveryViewModel.showWipeConfirmation()
                    }
                    recoveryViewModel.setAuthAction(PendingAuthAction.None)
                },
            )
        }
    }
}

@Composable
private fun Content(
    uiState: RecoveryUiState,
    walletExists: Boolean,
    onExportLogs: () -> Unit,
    onShowSeed: () -> Unit,
    onContactSupport: () -> Unit,
    onWipeApp: () -> Unit,
    onWipeConfirm: () -> Unit,
    onWipeCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .screen()
            .padding(horizontal = 16.dp)
    ) {
        AppTopBar(
            titleText = stringResource(R.string.security__recovery),
            onBackClick = null,
        )

        VerticalSpacer(16.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            BodyM(text = stringResource(R.string.security__recovery_text))

            VerticalSpacer(32.dp)

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryButton(
                    text = stringResource(R.string.lightning__export_logs),
                    onClick = onExportLogs,
                    isLoading = uiState.isExportingLogs,
                )

                SecondaryButton(
                    text = stringResource(R.string.security__display_seed),
                    onClick = onShowSeed,
                    enabled = walletExists,
                )

                SecondaryButton(
                    text = stringResource(R.string.security__contact_support),
                    onClick = onContactSupport,
                )

                SecondaryButton(
                    text = stringResource(R.string.security__wipe_app),
                    enabled = walletExists,
                    onClick = onWipeApp,
                )
            }
        }
    }

    if (uiState.showWipeConfirmation) {
        AppAlertDialog(
            onDismissRequest = onWipeCancel,
            title = stringResource(R.string.security__reset_dialog_title),
            text = stringResource(R.string.security__reset_dialog_desc),
            confirmText = stringResource(R.string.security__reset_confirm),
            onConfirm = onWipeConfirm,
            onDismiss = onWipeCancel,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            uiState = RecoveryUiState(),
            walletExists = true,
            onExportLogs = {},
            onShowSeed = {},
            onContactSupport = {},
            onWipeApp = {},
            onWipeConfirm = {},
            onWipeCancel = {},
        )
    }
}
