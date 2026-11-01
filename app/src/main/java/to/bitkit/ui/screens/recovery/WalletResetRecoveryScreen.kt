package to.bitkit.ui.screens.recovery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import to.bitkit.R
import to.bitkit.data.WalletResetRecovery
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.util.screen
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.viewmodels.WalletViewModel

@Composable
fun WalletResetRecoveryScreen(walletViewModel: WalletViewModel, modifier: Modifier = Modifier) {
    val walletState by walletViewModel.walletState.collectAsStateWithLifecycle()
    val busy by walletViewModel.isFinishingWalletReset.collectAsStateWithLifecycle()
    WalletResetRecoveryContent(
        recovery = walletState.walletResetRecovery,
        busy = busy,
        onRetry = walletViewModel::refreshWalletResetRecovery,
        onFinish = { walletViewModel.finishPendingWalletReset(it) },
        modifier = modifier
    )
}

@Composable
internal fun WalletResetRecoveryContent(
    recovery: WalletResetRecovery,
    busy: Boolean,
    onRetry: () -> Unit,
    onFinish: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirmation by remember(recovery) { mutableStateOf(false) }
    BackHandler {}
    Column(modifier = modifier.screen().padding(horizontal = 16.dp).testTag("WalletResetRecovery")) {
        AppTopBar(titleText = stringResource(R.string.security__recovery), onBackClick = null)
        VerticalSpacer(16.dp)
        if (recovery is WalletResetRecovery.Pending) {
            BodyM(text = stringResource(R.string.security__reset_pending_text))
        } else if (recovery == WalletResetRecovery.Unavailable) {
            BodyM(text = stringResource(R.string.security__reset_read_error))
        }
        VerticalSpacer(32.dp)
        when (recovery) {
            WalletResetRecovery.Clear -> Unit
            WalletResetRecovery.Loading -> PrimaryButton(
                text = null,
                isLoading = true,
                enabled = false,
                onClick = {},
            )
            WalletResetRecovery.Unavailable -> PrimaryButton(
                text = stringResource(R.string.common__retry),
                onClick = onRetry,
                modifier = Modifier.testTag("WalletResetReadRetry")
            )
            is WalletResetRecovery.Pending -> PrimaryButton(
                text = stringResource(R.string.security__reset_button_reset),
                isLoading = busy,
                enabled = !busy,
                onClick = { showConfirmation = true },
                modifier = Modifier.testTag("WalletResetFinish")
            )
        }
    }
    if (showConfirmation && recovery is WalletResetRecovery.Pending && !busy) {
        AppAlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = stringResource(R.string.security__reset_dialog_title),
            text = stringResource(R.string.security__reset_dialog_desc),
            confirmText = stringResource(R.string.security__reset_confirm),
            onConfirm = {
                showConfirmation = false
                onFinish(recovery.attemptId)
            },
            onDismiss = { showConfirmation = false },
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        WalletResetRecoveryContent(WalletResetRecovery.Pending("preview"), false, {}, {})
    }
}
