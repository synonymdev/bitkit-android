package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SheetValue
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.synonym.bitkitcore.UsdtDestination
import com.synonym.bitkitcore.UsdtTransfer
import com.synonym.bitkitcore.UsdtTransferStatus
import com.synonym.bitkitcore.usdtFormatAmount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.models.NewTransactionSheetDetails
import to.bitkit.models.NewTransactionSheetDirection
import to.bitkit.models.NewTransactionSheetType
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BiometricsView
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BottomSheet
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.WalletBalanceContent
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.screens.wallets.activity.components.EmptyActivityRow
import to.bitkit.ui.screens.wallets.activity.components.activityGroupTitleResource
import to.bitkit.ui.screens.wallets.send.SendPinCheckScreen
import to.bitkit.ui.settingsViewModel
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.shared.modifiers.sheetHeight
import to.bitkit.ui.shared.util.gradientBackground
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.rememberBiometricAuthSupported
import kotlin.time.Duration.Companion.seconds

@Composable
fun UsdtWalletCard(onClick: () -> Unit, viewModel: UsdtViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    UsdtRefreshEffect(viewModel, pending = wallet.transfers.any { it.status == UsdtTransferStatus.PENDING })
    VerticalSpacer(16.dp)
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        WalletBalanceContent(
            title = "USDT",
            icon = {
                CircularIcon(
                    icon = painterResource(R.drawable.ic_coins),
                    iconColor = Colors.Green,
                    backgroundColor = Colors.Green16,
                    size = 24.dp,
                    modifier = Modifier.padding(end = 4.dp)
                )
            },
            modifier = Modifier.clickableAlpha(onClick = onClick).padding(vertical = 4.dp)
                .padding(end = 8.dp).testTag("UsdtWallet"),
        ) {
            BodyMSB(
                if (settings.hideBalance) {
                    UiConstants.HIDE_BALANCE_SHORT
                } else {
                    wallet.balance?.let {
                        usdtFormatAmount(it)
                    } ?: "—"
                }
            )
        }
        VerticalDivider(color = Colors.Gray4)
        FillWidth()
    }
}

@Suppress("CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsdtWalletScreen(onBack: () -> Unit, viewModel: UsdtViewModel = hiltViewModel()) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(UsdtPage.WALLET) }
    val sending = page in listOf(UsdtPage.RECIPIENT, UsdtPage.MANUAL, UsdtPage.AMOUNT)
    DisposableEffect(viewModel, sending) {
        viewModel.isSendPresented = sending
        onDispose { viewModel.isSendPresented = false }
    }
    var selectedTransferId by rememberSaveable { mutableStateOf<String?>(null) }
    var recipient by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    val destinations by viewModel.destinations.collectAsStateWithLifecycle()
    var destination by remember { mutableStateOf(UsdtDestination.ARBITRUM) }
    var depositBlocking by remember { mutableStateOf(false) }
    val dismissalBlocked = state.busy || state.authenticationRequired || depositBlocking
    val dismiss = {
        if (!dismissalBlocked) {
            viewModel.edit()
            page = UsdtPage.WALLET
        }
    }
    val back = {
        when {
            state.busy -> Unit
            state.authenticationRequired -> viewModel.cancelAuthentication()
            state.submitted -> dismiss()
            state.quote != null -> viewModel.edit()
            page in listOf(UsdtPage.AMOUNT, UsdtPage.MANUAL) -> {
                viewModel.edit()
                page = if (page == UsdtPage.AMOUNT) UsdtPage.MANUAL else UsdtPage.RECIPIENT
            }
            else -> dismiss()
        }
    }
    BackHandler(enabled = page == UsdtPage.WALLET) {
        if (selectedTransferId != null) selectedTransferId = null else onBack()
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, state.submitted) {
        if (state.submitted) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.waitForTransfer()
            }
        }
    }
    val submittedStatus = wallet.transfers.firstOrNull { it.id == state.quote?.id }?.status
    val paymentSent = state.submitted && !state.busy && submittedStatus == UsdtTransferStatus.CONFIRMED
    UsdtRefreshEffect(
        viewModel,
        pending = wallet.transfers.any { it.status == UsdtTransferStatus.PENDING },
        receiving = page == UsdtPage.RECEIVE,
    ) {
        if (page == UsdtPage.RECEIVE && !depositBlocking) page = UsdtPage.WALLET
        page == UsdtPage.WALLET
    }
    val selectedTransfer = wallet.transfers.firstOrNull { it.id == selectedTransferId }
    if (selectedTransfer != null) {
        UsdtActivityDetail(selectedTransfer, settings.hideBalance) { selectedTransferId = null }
    } else {
        UsdtWalletContent(
            wallet = wallet,
            error = state.refreshError,
            hideBalance = settings.hideBalance,
            onReceive = {
                viewModel.edit()
                page = UsdtPage.RECEIVE
            },
            onSend = {
                recipient = ""
                amount = ""
                viewModel.edit()
                page = UsdtPage.RECIPIENT
            },
            onBack = onBack,
            onActivityClick = { selectedTransferId = it.id },
        )
    }

    state.warning?.let { warning ->
        AppAlertDialog(
            title = stringResource(R.string.common__are_you_sure),
            text = stringResource(warning.message),
            confirmText = stringResource(R.string.wallet__send_yes),
            dismissText = stringResource(R.string.common__cancel),
            onConfirm = viewModel::acceptWarning,
            onDismiss = viewModel::edit,
            modifier = Modifier.testTag(warning.testTag)
        )
    }
    if (page != UsdtPage.WALLET) {
        val blocked by rememberUpdatedState(dismissalBlocked)
        val sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { it != SheetValue.Hidden || !blocked },
        )
        BottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
            BackHandler(onBack = back)
            Column(
                modifier = Modifier.sheetHeight(isModal = true).gradientBackground()
                    .navigationBarsPadding().imePadding()
                    .padding(horizontal = if (paymentSent) 0.dp else 16.dp)
                    .padding(bottom = 16.dp)
            ) {
                when {
                    state.authenticationRequired -> UsdtAuthentication(
                        useBiometrics = settings.isBiometricEnabled,
                        onCancel = viewModel::cancelAuthentication,
                        onVerified = viewModel::authenticationVerified,
                    )
                    page == UsdtPage.RECEIVE -> UsdtDepositReceiveScreen(
                        viewModel = viewModel,
                        onBack = back,
                        onBlockingChange = { depositBlocking = it }
                    )
                    else -> UsdtPaymentContent(
                        wallet = wallet, state = state, page = page, recipient = recipient, amount = amount,
                        destination = destination,
                        destinations = destinations,
                        onDestinationChange = {
                            if (destination != it) {
                                destination = it
                                recipient = ""
                                amount = ""
                                viewModel.edit()
                            }
                        },
                        onRecipientChange = {
                            recipient = it
                            viewModel.edit()
                        },
                        onAmountChange = { amount = it },
                        onRecipientContinue = { value ->
                            viewModel.validateRecipient(value, destination) { request ->
                                recipient = request.recipient
                                amount = request.amount?.let { usdtFormatAmount(it) }.orEmpty()
                                page = UsdtPage.AMOUNT
                            }
                        },
                        onPageChange = {
                            if (!state.busy) {
                                viewModel.edit()
                                page = it
                            }
                        },
                        onBack = back, onReview = { viewModel.quote(recipient, amount, destination) },
                        onConfirm = { viewModel.confirm() }, onDone = dismiss,
                        hideBalance = settings.hideBalance,
                        onDetails = {
                            selectedTransferId = state.quote?.id
                            dismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun UsdtWalletContent(
    wallet: UsdtWalletState,
    error: Int?,
    hideBalance: Boolean,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onActivityClick: (UsdtTransfer) -> Unit = {},
) {
    val settings = settingsViewModel
    val allowSwipe by settings?.enableSwipeToHideBalance?.collectAsStateWithLifecycle() ?: remember {
        mutableStateOf(false)
    }
    Column(modifier = Modifier.fillMaxSize()) {
        AppTopBar(titleText = "USDT", onBackClick = onBack, actions = { DrawerNavIcon() })
        LazyColumn(modifier = Modifier.padding(horizontal = 16.dp)) {
            item {
                VerticalSpacer(16.dp)
                UsdtAmountHeader(
                    amount = wallet.balance?.let { usdtFormatAmount(it) } ?: "—",
                    network = "Arbitrum One",
                    hideBalance = hideBalance,
                    onToggleHide = if (allowSwipe) ({ settings?.setHideBalance(!hideBalance) }) else null,
                    modifier = Modifier.testTag("UsdtBalance")
                )
                VerticalSpacer(32.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    SecondaryButton(
                        text = stringResource(R.string.usdt__receive),
                        onClick = onReceive,
                        icon = { Icon(painterResource(R.drawable.ic_received), null, tint = Colors.Green) },
                        modifier = Modifier.weight(1f).testTag("UsdtReceive")
                    )
                    PrimaryButton(
                        text = stringResource(R.string.usdt__send),
                        onClick = onSend,
                        icon = { Icon(painterResource(R.drawable.ic_sent), null, tint = Colors.Green) },
                        modifier = Modifier.weight(1f).testTag("UsdtSend")
                    )
                }
                VerticalSpacer(32.dp)
                error?.let {
                    BodyS(stringResource(it), color = Colors.Brand)
                    VerticalSpacer(16.dp)
                }
                if (wallet.transfers.isEmpty()) {
                    Caption13Up(stringResource(R.string.usdt__activity), color = Colors.White64)
                    VerticalSpacer(16.dp)
                    EmptyActivityRow(onClick = onReceive)
                }
            }
            itemsIndexed(wallet.transfers, key = { _, transfer -> transfer.id }) { index, transfer ->
                val heading = activityGroupTitleResource(transfer.timestamp)
                if (index == 0 || activityGroupTitleResource(wallet.transfers[index - 1].timestamp) != heading) {
                    Caption13Up(
                        stringResource(heading),
                        color = Colors.White64,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                UsdtActivityRow(transfer, hideBalance, onClick = { onActivityClick(transfer) })
                VerticalSpacer(16.dp)
            }
            item {
                if (!wallet.historyComplete) BodyS(stringResource(R.string.usdt__history_sync), color = Colors.White64)
                VerticalSpacer(120.dp)
            }
        }
    }
}

internal val UsdtDestination.label: String get() = when (this) {
    UsdtDestination.STABLE -> "Stable"
    UsdtDestination.ETHEREUM -> "Ethereum"
    UsdtDestination.ARBITRUM -> "Arbitrum One"
    UsdtDestination.POLYGON -> "Polygon"
    UsdtDestination.PLASMA -> "Plasma"
    UsdtDestination.BASE -> "Base"
    UsdtDestination.BSC -> "BNB Smart Chain"
    UsdtDestination.SOLANA -> "Solana"
    UsdtDestination.TRON -> "Tron"
}

@Composable
private fun UsdtRefreshEffect(
    viewModel: UsdtViewModel,
    pending: Boolean = false,
    receiving: Boolean = false,
    onReceived: () -> Boolean = { true },
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val app = appViewModel
    val handleReceived by rememberUpdatedState(onReceived)
    val isPending by rememberUpdatedState(pending)
    val isReceiving by rememberUpdatedState(receiving)
    LaunchedEffect(lifecycle, viewModel, app) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                viewModel.receivedTxs.collect { transfer ->
                    if (handleReceived()) {
                        app?.showTransactionSheet(
                            NewTransactionSheetDetails(
                                type = NewTransactionSheetType.ONCHAIN,
                                direction = NewTransactionSheetDirection.RECEIVED,
                                usdtAmount = transfer.amount,
                            )
                        )
                    }
                }
            }
            while (true) {
                viewModel.refresh(history = true)
                delay(if (isReceiving || isPending) 10.seconds else 30.seconds)
            }
        }
    }
}

@Composable
internal fun UsdtAuthentication(useBiometrics: Boolean, onCancel: () -> Unit, onVerified: () -> Unit) {
    val supported = rememberBiometricAuthSupported()
    var fallbackToPin by remember { mutableStateOf(false) }
    if (useBiometrics && supported && !fallbackToPin) {
        BiometricsView(onSuccess = onVerified, onFailure = { fallbackToPin = true })
    } else {
        SendPinCheckScreen(onBack = onCancel, onSuccess = onVerified)
    }
}

internal enum class UsdtPage {
    WALLET,
    RECEIVE,
    RECIPIENT,
    MANUAL,
    AMOUNT,
}

@Preview
@Composable
private fun Preview() {
    AppThemeSurface {
        UsdtWalletContent(UsdtWalletState(balance = 100_000_000u), null, false, {}, {}, {})
    }
}
