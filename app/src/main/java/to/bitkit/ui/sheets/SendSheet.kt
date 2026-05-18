package to.bitkit.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.ConnectivityState
import to.bitkit.ui.components.ConnectionIssuesView
import to.bitkit.ui.components.SyncNodeView
import to.bitkit.ui.navigateTo
import to.bitkit.ui.screens.scanner.QrScanningScreen
import to.bitkit.ui.screens.wallets.send.AddTagScreen
import to.bitkit.ui.screens.wallets.send.PIN_CHECK_RESULT_KEY
import to.bitkit.ui.screens.wallets.send.SendAddressScreen
import to.bitkit.ui.screens.wallets.send.SendAmountScreen
import to.bitkit.ui.screens.wallets.send.SendCoinSelectionScreen
import to.bitkit.ui.screens.wallets.send.SendConfirmScreen
import to.bitkit.ui.screens.wallets.send.SendContactSelectScreen
import to.bitkit.ui.screens.wallets.send.SendContactSelectViewModel
import to.bitkit.ui.screens.wallets.send.SendErrorScreen
import to.bitkit.ui.screens.wallets.send.SendFeeCustomScreen
import to.bitkit.ui.screens.wallets.send.SendFeeRateScreen
import to.bitkit.ui.screens.wallets.send.SendFeeViewModel
import to.bitkit.ui.screens.wallets.send.SendPendingScreen
import to.bitkit.ui.screens.wallets.send.SendPendingViewModel
import to.bitkit.ui.screens.wallets.send.SendPinCheckScreen
import to.bitkit.ui.screens.wallets.send.SendQuickPayScreen
import to.bitkit.ui.screens.wallets.send.SendRecipientScreen
import to.bitkit.ui.screens.wallets.withdraw.WithdrawConfirmScreen
import to.bitkit.ui.screens.wallets.withdraw.WithdrawErrorScreen
import to.bitkit.ui.settings.support.SupportScreen
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.utils.composableWithDefaultTransitions
import to.bitkit.ui.utils.navigationWithDefaultTransitions
import to.bitkit.viewmodels.AppViewModel
import to.bitkit.viewmodels.SendEffect
import to.bitkit.viewmodels.SendEvent
import to.bitkit.viewmodels.WalletViewModel

@Suppress("CyclomaticComplexMethod")
@Composable
fun SendSheet(
    appViewModel: AppViewModel,
    walletViewModel: WalletViewModel,
    startDestination: SendRoute = SendRoute.Recipient,
) {
    val connectivityState by appViewModel.isOnline.collectAsStateWithLifecycle()
    val isOffline by remember { derivedStateOf { connectivityState != ConnectivityState.CONNECTED } }
    val lightningState by walletViewModel.lightningState.collectAsStateWithLifecycle()

    val shouldShowSyncOverlay by remember {
        derivedStateOf {
            if (!lightningState.nodeLifecycleState.isRunning()) return@derivedStateOf true
            val hasAnyChannels = lightningState.channels.isNotEmpty()
            hasAnyChannels && lightningState.channels.none { it.isUsable }
        }
    }

    LaunchedEffect(startDestination) {
        // always reset state on new user-initiated send
        if (startDestination == SendRoute.Recipient) {
            appViewModel.resetSendState()
            appViewModel.resetQuickPay()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .sheetHeight(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .testTag("SendSheet"),
        ) {
            val navController = rememberNavController()
            LaunchedEffect(appViewModel, navController) {
                appViewModel.sendEffect.collect {
                    when (it) {
                        is SendEffect.NavigateToAmount -> navController.navigateTo(SendRoute.Amount)
                        is SendEffect.NavigateToAddress -> navController.navigateTo(SendRoute.Address)
                        is SendEffect.NavigateToScan -> navController.navigateTo(SendRoute.QrScanner)
                        is SendEffect.NavigateToCoinSelection -> navController.navigateTo(SendRoute.CoinSelection)
                        is SendEffect.NavigateToConfirm -> navController.navigateTo(SendRoute.Confirm)
                        is SendEffect.PopBack -> navController.popBackStack(it.route, inclusive = false)
                        is SendEffect.PaymentSuccess -> {
                            appViewModel.clearClipboardForAutoRead()
                            navController.navigateTo(SendRoute.Success) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        }

                        is SendEffect.NavigateToQuickPay -> navController.navigateTo(SendRoute.QuickPay)
                        is SendEffect.NavigateToWithdrawConfirm -> navController.navigateTo(
                            SendRoute.WithdrawConfirm
                        )
                        is SendEffect.NavigateToWithdrawError -> navController.navigateTo(SendRoute.WithdrawError)
                        is SendEffect.NavigateToFee -> navController.navigateTo(SendRoute.FeeRate)
                        is SendEffect.NavigateToFeeCustom -> navController.navigateTo(SendRoute.FeeCustom)
                        is SendEffect.NavigateToComingSoon -> navController.navigateTo(SendRoute.ComingSoon)
                        is SendEffect.NavigateToContacts -> navController.navigateTo(SendRoute.ContactSelect)
                        is SendEffect.NavigateToPending -> navController.navigateTo(
                            SendRoute.Pending(it.paymentHash, it.amount)
                        ) { popUpTo(startDestination) { inclusive = true } }
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = startDestination,
            ) {
                composableWithDefaultTransitions<SendRoute.Recipient> {
                    SendRecipientScreen(
                        onEvent = { appViewModel.setSendEvent(it) }
                    )
                }
                composableWithDefaultTransitions<SendRoute.Address> {
                    val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                    SendAddressScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onEvent = { appViewModel.setSendEvent(it) },
                    )
                }
                composableWithDefaultTransitions<SendRoute.ContactSelect> {
                    SendContactSelectScreen(
                        viewModel = hiltViewModel<SendContactSelectViewModel>(),
                        onBack = {
                            appViewModel.clearActiveContactPaymentContext()
                            navController.popBackStack()
                        },
                        onOpenPayment = { paymentRequest, publicKey ->
                            appViewModel.openContactPayment(paymentRequest, publicKey)
                        },
                    )
                }
                composableWithDefaultTransitions<SendRoute.Amount> {
                    val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                    val lightningState by walletViewModel.lightningState.collectAsStateWithLifecycle()
                    SendAmountScreen(
                        uiState = uiState,
                        nodeLifecycleState = lightningState.nodeLifecycleState,
                        canGoBack = startDestination != SendRoute.Amount,
                        onBack = {
                            if (!navController.popBackStack()) {
                                appViewModel.hideSheet()
                            }
                        },
                        onEvent = { appViewModel.setSendEvent(it) }
                    )
                }
                composableWithDefaultTransitions<SendRoute.QrScanner> {
                    QrScanningScreen(
                        onBack = { navController.popBackStack() },
                        onScanSuccess = {
                            navController.popBackStack()
                            appViewModel.onScanResult(data = it, routePubkyKeys = true)
                        },
                    )
                }
                composableWithDefaultTransitions<SendRoute.CoinSelection> {
                    val sendUiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                    SendCoinSelectionScreen(
                        requiredAmount = sendUiState.amount,
                        address = sendUiState.address,
                        onBack = { navController.popBackStack() },
                        onContinue = { utxos -> appViewModel.setSendEvent(SendEvent.CoinSelectionContinue(utxos)) },
                    )
                }
                navigationWithDefaultTransitions<SendRoute.FeeNav>(
                    startDestination = SendRoute.FeeRate,
                ) {
                    composableWithDefaultTransitions<SendRoute.FeeRate> {
                        val sendUiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                        val parentEntry = remember(it) { navController.getBackStackEntry(SendRoute.FeeNav) }
                        SendFeeRateScreen(
                            sendUiState = sendUiState,
                            viewModel = hiltViewModel<SendFeeViewModel>(parentEntry),
                            onBack = { navController.popBackStack() },
                            onContinue = { navController.popBackStack() },
                            onSelect = { speed -> appViewModel.onSelectSpeed(speed) },
                            onSelectInstant = {
                                appViewModel.switchToLightning()
                                navController.popBackStack()
                            },
                        )
                    }
                    composableWithDefaultTransitions<SendRoute.FeeCustom> {
                        val parentEntry = remember(it) { navController.getBackStackEntry(SendRoute.FeeNav) }
                        SendFeeCustomScreen(
                            viewModel = hiltViewModel<SendFeeViewModel>(parentEntry),
                            onBack = { navController.popBackStack() },
                            onContinue = { speed -> appViewModel.setTransactionSpeed(speed) },
                        )
                    }
                }
                composableWithDefaultTransitions<SendRoute.Confirm> {
                    val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                    val lightningState by walletViewModel.lightningState.collectAsStateWithLifecycle()

                    SendConfirmScreen(
                        savedStateHandle = it.savedStateHandle,
                        uiState = uiState,
                        isNodeRunning = lightningState.nodeLifecycleState.isRunning(),
                        canGoBack = startDestination != SendRoute.Confirm,
                        onBack = {
                            if (!navController.popBackStack()) {
                                appViewModel.hideSheet()
                            }
                        },
                        onEvent = { e -> appViewModel.setSendEvent(e) },
                        onClickAddTag = { navController.navigateTo(SendRoute.AddTag) },
                        onClickTag = { tag -> appViewModel.removeTag(tag) },
                        onNavigateToPin = { navController.navigateTo(SendRoute.PinCheck) },
                    )
                }
                composableWithDefaultTransitions<SendRoute.Success> {
                    val sendDetail by appViewModel.successSendUiState.collectAsStateWithLifecycle()
                    NewTransactionSheetView(
                        details = sendDetail,
                        onCloseClick = { appViewModel.hideSheet() },
                        onDetailClick = { appViewModel.onClickSendDetail() },
                        modifier = Modifier
                            .fillMaxSize()
                            .gradientBackground()
                            .navigationBarsPadding()
                            .testTag("SendSuccess")
                    )
                }
                composableWithDefaultTransitions<SendRoute.WithdrawConfirm> {
                    val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                    WithdrawConfirmScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onConfirm = { appViewModel.onConfirmWithdraw() },
                    )
                }
                composableWithDefaultTransitions<SendRoute.WithdrawError> {
                    val uiState by appViewModel.sendUiState.collectAsStateWithLifecycle()
                    WithdrawErrorScreen(
                        uiState = uiState,
                        onBack = { navController.popBackStack() },
                        onClickScan = { navController.navigateTo(SendRoute.QrScanner) },
                        onClickSupport = { navController.navigateTo(SendRoute.Support) },
                    )
                }
                // TODO navigate to main support screen, not inside SEND sheet
                composableWithDefaultTransitions<SendRoute.Support> {
                    SupportScreen(navController)
                }
                composableWithDefaultTransitions<SendRoute.AddTag> {
                    AddTagScreen(
                        onBack = { navController.popBackStack() },
                        onTagSelected = { tag ->
                            appViewModel.addTagToSelected(tag)
                            navController.popBackStack()
                        },
                        tqgInputTestTag = "TagInputSend",
                        addButtonTestTag = "SendTagsSubmit",
                    )
                }
                composableWithDefaultTransitions<SendRoute.PinCheck> {
                    SendPinCheckScreen(
                        onBack = {
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set(PIN_CHECK_RESULT_KEY, false)
                            navController.popBackStack()
                        },
                        onSuccess = {
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set(PIN_CHECK_RESULT_KEY, true)
                            navController.popBackStack()
                            appViewModel.setSendEvent(SendEvent.PayConfirmed)
                        },
                    )
                }
                composableWithDefaultTransitions<SendRoute.QuickPay> {
                    val quickPayData by appViewModel.quickPayData.collectAsStateWithLifecycle()
                    SendQuickPayScreen(
                        quickPayData = requireNotNull(quickPayData),
                        onPaymentComplete = { paymentHash, amountWithFee ->
                            appViewModel.onSendSuccess(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.LIGHTNING,
                                    direction = NewTransactionSheetDirection.SENT,
                                    paymentHashOrTxId = paymentHash,
                                    sats = amountWithFee,
                                ),
                            )
                        },
                        onPaymentPending = { paymentHash, amount ->
                            appViewModel.preserveContactPaymentContext(paymentHash)
                            navController.navigateTo(SendRoute.Pending(paymentHash, amount)) {
                                popUpTo(startDestination) { inclusive = true }
                            }
                        },
                        onShowError = { errorMessage ->
                            appViewModel.clearActiveContactPaymentContext()
                            navController.navigateTo(SendRoute.Error(errorMessage))
                        }
                    )
                }
                composableWithDefaultTransitions<SendRoute.Pending> {
                    val route = it.toRoute<SendRoute.Pending>()
                    SendPendingScreen(
                        paymentHash = route.paymentHash,
                        amount = route.amount,
                        onPaymentSuccess = { paymentHash ->
                            appViewModel.onSendSuccess(
                                NewTransactionSheetDetails(
                                    type = NewTransactionSheetType.LIGHTNING,
                                    direction = NewTransactionSheetDirection.SENT,
                                    paymentHashOrTxId = paymentHash,
                                    sats = route.amount,
                                ),
                            )
                        },
                        onPaymentError = {
                            navController.navigateTo(SendRoute.Error()) {
                                popUpTo<SendRoute.Pending> { inclusive = true }
                            }
                        },
                        onClose = { appViewModel.hideSheet() },
                        onViewDetails = { rawId -> appViewModel.navigateToActivity(rawId) },
                        viewModel = hiltViewModel<SendPendingViewModel>(),
                    )
                }
                composableWithDefaultTransitions<SendRoute.ComingSoon> {
                    ComingSoonSheetContent(
                        onWalletOverviewClick = { appViewModel.hideSheet() },
                        onBack = { navController.popBackStack() },
                    )
                }
                composableWithDefaultTransitions<SendRoute.Error> {
                    val route = it.toRoute<SendRoute.Error>()
                    SendErrorScreen(
                        message = route.message,
                        onRetry = {
                            navController.navigateTo(SendRoute.Recipient) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        },
                        onClose = {
                            appViewModel.hideSheet()
                        }
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = isOffline,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConnectionIssuesView(titleText = stringResource(R.string.wallet__send_bitcoin))
        }

        AnimatedVisibility(
            visible = shouldShowSyncOverlay && !isOffline,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            SyncNodeView(
                modifier = Modifier
                    .fillMaxSize()
                    .gradientBackground()
                    .navigationBarsPadding()
            )
        }
    }
}

sealed interface SendRoute {
    @Serializable
    data object Recipient : SendRoute

    @Serializable
    data object Address : SendRoute

    @Serializable
    data object ContactSelect : SendRoute

    @Serializable
    data object Amount : SendRoute

    @Serializable
    data object QrScanner : SendRoute

    @Serializable
    data object WithdrawConfirm : SendRoute

    @Serializable
    data object WithdrawError : SendRoute

    @Serializable
    data object Support : SendRoute

    @Serializable
    data object AddTag : SendRoute

    @Serializable
    data object PinCheck : SendRoute

    @Serializable
    data object CoinSelection : SendRoute

    @Serializable
    data object QuickPay : SendRoute

    @Serializable
    data object FeeNav : SendRoute

    @Serializable
    data object FeeRate : SendRoute

    @Serializable
    data object FeeCustom : SendRoute

    @Serializable
    data object Confirm : SendRoute

    @Serializable
    data object Success : SendRoute

    @Serializable
    data object ComingSoon : SendRoute

    @Serializable
    data class Pending(val paymentHash: String, val amount: Long) : SendRoute

    @Serializable
    data class Error(val message: String? = null) : SendRoute
}
