package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import to.bitkit.R
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.screens.subscriptions.SubscriptionSuccess
import to.bitkit.ui.screens.wallets.send.AutoPayOverlay
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.theme.Colors
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendUiState
import kotlin.time.Duration.Companion.seconds

@Composable
fun PaykitUsdtReview(
    uiState: SendUiState,
    app: AppViewModel,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: UsdtViewModel = hiltViewModel(),
    autoPayContent: (@Composable () -> Unit)? = null,
) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val paymentActive = state.busy || state.authenticationRequired
    val reviewing = state.quote != null && !state.submitted
    LaunchedEffect(reviewing, lifecycle, app) {
        if (!reviewing) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val finishReview = app.beginPaymentReview()
            try {
                awaitCancellation()
            } finally {
                finishReview()
            }
        }
    }
    DisposableEffect(paymentActive) {
        app.updatePaykitUsdtBusy(paymentActive)
        onDispose { app.updatePaykitUsdtBusy(false) }
    }
    var details by remember { mutableStateOf(false) }
    var automaticPayment by remember { mutableStateOf(false) }
    val back = {
        if (!paymentActive) {
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
        preparePayment(viewModel, app, uiState) { automaticPayment = it }
    }
    LaunchedEffect(state.submitted, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reconcileSubmittedPayment(viewModel, app)
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
    val initialPaymentConfirmed = uiState.isInitialSubscriptionPayment &&
        transfer?.status == UsdtTransferStatus.CONFIRMED
    if (initialPaymentConfirmed && state.submitted && !state.busy) {
        SubscriptionSuccess(onClose = onDone, paymentType = null, usdtAmount = transfer.amount)
    } else if (details && transfer != null) {
        UsdtActivityDetail(transfer, settings.hideBalance, wallet.address) { details = false }
    } else {
        Box {
            Column(
                Modifier.sheetHeight().background(Colors.Gray7)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp)
            ) {
                if (state.authenticationRequired) {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        UsdtAuthentication(
                            settings.isBiometricEnabled,
                            viewModel::cancelAuthentication,
                            viewModel::authenticationVerified
                        )
                    }
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
                            automaticPayment = false
                            viewModel.quote(
                                requireNotNull(uiState.usdtRecipient),
                                uiState.paykitAmount,
                                UsdtDestination.ARBITRUM
                            )
                        },
                        onConfirm = {
                            automaticPayment = false
                            viewModel.confirm()
                        },
                        onDone = onDone,
                        onDetails = { details = true },
                        onRetryRecovery = { viewModel.retryRecovery() },
                        hideBalance = settings.hideBalance,
                        amountEditable = false,
                        isPaymentRequest = uiState.isPaymentRequest,
                        paymentRequestNote = uiState.paymentRequestNote,
                        isMerchantRequest = uiState.isMerchantPaymentRequest,
                        contact = uiState.contactPaymentProfile,
                    )
                }
            }
            AutoPayOverlay(isVisible = automaticPayment && state.busy, content = autoPayContent)
        }
    }
}

internal suspend fun preparePayment(
    viewModel: UsdtViewModel,
    app: AppViewModel,
    uiState: SendUiState,
    onAutomaticPayment: (Boolean) -> Unit = {},
) {
    val automaticPayment = app.takeInitialUsdtSubscriptionAuthorization()
    onAutomaticPayment(automaticPayment)
    if (automaticPayment || viewModel.state.value.quote == null) {
        viewModel.quote(
            requireNotNull(uiState.usdtRecipient),
            uiState.paykitAmount,
            UsdtDestination.ARBITRUM
        ).join()
    }
    if (automaticPayment && viewModel.state.value.quote != null) viewModel.confirm()
}

private suspend fun reconcileSubmittedPayment(viewModel: UsdtViewModel, app: AppViewModel) {
    if (!viewModel.state.value.submitted) return
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
