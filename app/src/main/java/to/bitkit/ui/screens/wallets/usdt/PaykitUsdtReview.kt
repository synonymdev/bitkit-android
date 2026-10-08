package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtTransferStatus
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendUiState
import kotlin.time.Duration.Companion.seconds

@Composable
fun PaykitUsdtReview(
    uiState: SendUiState,
    app: AppViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: UsdtViewModel = hiltViewModel()
) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(state.busy, state.authenticationRequired) {
        app.updatePaykitUsdtBusy(state.busy || state.authenticationRequired)
        onDispose { app.updatePaykitUsdtBusy(false) }
    }
    var details by remember { mutableStateOf(false) }
    val back = {
        if (!state.busy && !state.authenticationRequired) {
            if (state.submitted) onDone() else onBack()
        }
    }
    DisposableEffect(viewModel) {
        viewModel.sendPayment = app::sendPaykitUsdt
        viewModel.isSendPresented = true
        onDispose {
            viewModel.sendPayment = null
            viewModel.isSendPresented = false
        }
    }
    LaunchedEffect(Unit) {
        viewModel.quote(requireNotNull(uiState.usdtRecipient), uiState.paykitAmount, UsdtDestination.ARBITRUM)
    }
    LaunchedEffect(state.submitted, lifecycle) {
        if (state.submitted) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                reconcileSubmittedPayment(viewModel, app)
            }
        }
    }
    BackHandler { if (state.authenticationRequired) viewModel.cancelAuthentication() else back() }
    state.warning?.let { warning ->
        AppAlertDialog(
            title = stringResource(R.string.common__are_you_sure),
            text = stringResource(warning.message),
            confirmText = stringResource(R.string.wallet__send_yes),
            dismissText = stringResource(R.string.common__cancel),
            onConfirm = viewModel::acceptWarning,
            onDismiss = viewModel::edit
        )
    }
    val transfer = wallet.transfers.firstOrNull { it.id == state.quote?.id }
    if (details && transfer != null) {
        UsdtActivityDetail(transfer, settings.hideBalance) { details = false }
    } else {
        Column(
            Modifier.sheetHeight().gradientBackground().padding(
                horizontal = if (state.submitted && transfer?.status == UsdtTransferStatus.CONFIRMED) 0.dp else 16.dp
            )
        ) {
            if (state.authenticationRequired) {
                UsdtAuthentication(
                    settings.isBiometricEnabled,
                    viewModel::cancelAuthentication,
                    viewModel::authenticationVerified
                )
            } else {
                UsdtPaymentContent(
                    wallet, state, UsdtPage.AMOUNT,
                    uiState.usdtRecipient.orEmpty(), uiState.paykitAmount, UsdtDestination.ARBITRUM,
                    onRecipientChange = {},
                    onAmountChange = {},
                    onRecipientContinue = {},
                    onPageChange = { back() },
                    onBack = back,
                    onReview = {
                        viewModel.quote(
                            requireNotNull(uiState.usdtRecipient),
                            uiState.paykitAmount,
                            UsdtDestination.ARBITRUM
                        )
                    },
                    onConfirm = {
                        viewModel.confirm()
                    },
                    onDone = onDone,
                    onDetails = { details = true },
                    hideBalance = settings.hideBalance,
                    amountEditable = false,
                    isPaymentRequest = uiState.isPaymentRequest,
                    paymentRequestNote = uiState.paymentRequestNote,
                    contact = uiState.contactPaymentProfile,
                )
            }
        }
    }
}

private suspend fun reconcileSubmittedPayment(viewModel: UsdtViewModel, app: AppViewModel) {
    viewModel.waitForTransfer()
    while (viewModel.wallet.value.transfers.any {
            it.id == viewModel.state.value.quote?.id && it.status == UsdtTransferStatus.PENDING
        }
    ) {
        delay(10.seconds)
        viewModel.refresh()
    }
    app.reconcilePaykitUsdt()
}
