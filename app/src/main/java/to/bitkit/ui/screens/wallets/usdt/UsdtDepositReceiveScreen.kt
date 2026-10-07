package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtDepositNetwork
import com.synonym.bitkitcore.usdtFormatAmount
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.FillWidth
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.SendCell
import to.bitkit.ui.components.TertiaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.activity.components.ActivityRowSurface
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.receive.ReceiveTab
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.Colors
import java.text.NumberFormat
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Suppress("CyclomaticComplexMethod")
@Composable
internal fun ColumnScope.UsdtDepositReceiveScreen(
    viewModel: UsdtViewModel,
    tabs: ImmutableList<ReceiveTab>,
    onSelectTab: (ReceiveTab) -> Unit,
    onBack: () -> Unit,
    onBlockingChange: (Boolean) -> Unit,
    onContacts: (() -> Unit)? = null,
    deposits: UsdtDepositViewModel = hiltViewModel(),
) {
    val wallet by viewModel.wallet.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val walletUi by viewModel.state.collectAsStateWithLifecycle()
    val state by deposits.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(ReceivePage.ADDRESS) }
    var amount by remember { mutableStateOf("") }
    var refundAddress by remember(state.detail?.deposit?.id) { mutableStateOf("") }
    var approved by remember { mutableStateOf<RefundApproval?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var authentication by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val back = {
        if (authentication) {
            authentication = false
            approved = null
        } else if (!deposits.back()) {
            when (page) {
                ReceivePage.FEES -> {
                    deposits.selectNetwork(state.network)
                    page = ReceivePage.AMOUNT
                }
                ReceivePage.AMOUNT -> {
                    if (state.network != null && state.address == null) deposits.selectNetwork(null)
                    page = ReceivePage.ADDRESS
                }
                ReceivePage.NETWORKS -> page = ReceivePage.ADDRESS
                ReceivePage.ADDRESS -> onBack()
            }
        }
    }
    val refund = {
        approved?.let { deposits.refund(it.id, it.offset, it.address, it.network) }
        approved = null
    }
    LaunchedEffect(state.busy, authentication) { onBlockingChange(state.busy || authentication) }
    DisposableEffect(Unit) { onDispose { onBlockingChange(false) } }
    val pendingId = state.detail?.takeIf {
        (it.order?.status ?: it.deposit.status) !in listOf("completed", "refunded")
    }?.deposit?.id
    DepositPolling(pendingId, lifecycle, { confirmation || authentication || approved != null }) {
        deposits.detail(it)
    }
    LaunchedEffect(state.address) { if (state.address != null) page = ReceivePage.FEES }
    UsdtRefreshEffect(viewModel, receiving = true, onReceived = { !state.busy && !authentication && !confirmation })
    BackHandler(onBack = back)
    if (authentication) {
        UsdtAuthentication(
            useBiometrics = settings.isBiometricEnabled,
            onCancel = {
                authentication = false
                approved = null
            },
            onVerified = {
                authentication = false
                refund()
            },
        )
    } else {
        SheetTopBar(
            titleText = stringResource(
                if (state.history) {
                    R.string.usdt__deposit_history
                } else {
                    when (page) {
                        ReceivePage.ADDRESS -> R.string.usdt__receive_title
                        ReceivePage.NETWORKS -> R.string.usdt__receive_network_title
                        ReceivePage.AMOUNT -> R.string.usdt__receive_amount_title
                        ReceivePage.FEES -> R.string.usdt__receive_fees_title
                    }
                }
            ),
            onBack = back.takeIf { state.history || page != ReceivePage.ADDRESS },
            action = if (page == ReceivePage.ADDRESS && !state.history && onContacts != null) {
                {
                    IconButton(onClick = onContacts) {
                        Icon(
                            painterResource(R.drawable.ic_users),
                            stringResource(R.string.wallet__payment_request_choose_recipient)
                        )
                    }
                }
            } else {
                null
            },
        )
        VerticalSpacer(16.dp)
        if (page == ReceivePage.ADDRESS && !state.history) {
            CustomTabRowWithSpacing(
                tabs = tabs,
                currentTabIndex = tabs.indexOf(ReceiveTab.USDT),
                onTabChange = onSelectTab
            )
            VerticalSpacer(16.dp)
        }
        walletUi.error?.let { BodyS(stringResource(it), color = Colors.Brand) }
        Content(
            page = page,
            onPage = { page = it },
            wallet = wallet,
            receiveError = walletUi.refreshError,
            state = state,
            amount = amount,
            refundAddress = refundAddress,
            hideBalance = settings.hideBalance,
            onNetwork = {
                if (state.network != it) {
                    amount = ""
                    deposits.selectNetwork(it)
                }
                page = if (it == null || state.address != null && state.network == it) {
                    ReceivePage.ADDRESS
                } else {
                    ReceivePage.AMOUNT
                }
            },
            onAmount = {
                if (amount != it) deposits.clearAmountError()
                amount = it
            },
            onRefundAddress = { refundAddress = it },
            onCreate = {
                if (state.network == null) page = ReceivePage.ADDRESS else deposits.prepare(amount)
            },
            onHistory = { deposits.history() },
            onMore = { state.nextOffset?.let { deposits.history(it) } },
            onDetail = { scope.launch { deposits.detail(it) } },
            onRefund = { if (!deposits.state.value.busy) confirmation = true },
            onRefresh = {
                deposits.selectNetwork(state.network)
                page = ReceivePage.AMOUNT
            },
        )
    }
    if (confirmation) {
        RefundDialog(
            network = state.detail?.deposit?.network.orEmpty() + "\n" + state.detail?.deposit?.id.orEmpty() +
                "\n" + state.detail?.deposit?.amount.usdtText(settings.hideBalance),
            address = refundAddress,
            onCancel = { confirmation = false },
            onConfirm = {
                confirmation = false
                val approval = refundApproval(state, refundAddress)
                if (approval != null) {
                    approved = approval
                    scope.launch {
                        try {
                            viewModel.readPaymentSettings { paymentSettings ->
                                if (approved != approval) return@readPaymentSettings
                                if (paymentSettings.isPinEnabled && paymentSettings.isPinForPaymentsEnabled) {
                                    authentication = true
                                } else {
                                    refund()
                                }
                            }
                        } finally {
                            if (!authentication && approved == approval) approved = null
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun DepositPolling(
    id: String?,
    lifecycle: Lifecycle,
    isPaused: () -> Boolean,
    refresh: suspend (String) -> Unit
) {
    LaunchedEffect(id, lifecycle, isPaused()) {
        if (id == null || isPaused()) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(10.seconds)
                if (!isPaused()) refresh(id)
            }
        }
    }
}

private data class RefundApproval(
    val id: String,
    val offset: UInt,
    val address: String,
    val network: UsdtDepositNetwork,
)

private fun refundApproval(state: DepositUiState, address: String): RefundApproval? {
    val deposit = state.detail?.deposit ?: return null
    val network = deposit.network.depositNetwork() ?: return null
    return RefundApproval(deposit.id, state.offset, address, network)
}

@Composable
private fun RefundDialog(network: String, address: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.usdt__deposit_refund)) },
        text = {
            Text(
                network.uppercase() + "\n" + address + "\n\n" +
                    stringResource(R.string.usdt__deposit_refund_note)
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.usdt__deposit_refund)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common__cancel)) } },
    )
}

@Composable
private fun ColumnScope.Content(
    page: ReceivePage,
    onPage: (ReceivePage) -> Unit,
    wallet: UsdtWalletState,
    receiveError: Int?,
    state: DepositUiState,
    amount: String,
    refundAddress: String,
    hideBalance: Boolean,
    onNetwork: (UsdtDepositNetwork?) -> Unit,
    onAmount: (String) -> Unit,
    onRefundAddress: (String) -> Unit,
    onCreate: () -> Unit,
    onHistory: () -> Unit,
    onMore: () -> Unit,
    onDetail: (String) -> Unit,
    onRefund: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (state.history) {
        DepositHistory(state, refundAddress, hideBalance, onRefundAddress, onRefund, onMore, onDetail, onHistory)
        return
    }
    when (page) {
        ReceivePage.NETWORKS -> NetworkSelector(state, onNetwork, onHistory)
        ReceivePage.AMOUNT -> UsdtReceiveAmountContent(
            amount,
            state.network == null,
            state.busy,
            state.error,
            state.amountLimits,
            onAmount,
            onCreate
        )
        ReceivePage.FEES -> state.address?.let { address ->
            UsdtAmountHeader(usdtFormatAmount(address.amount), state.network?.label ?: "Arbitrum One")
            VerticalSpacer(24.dp)
            BodyM(stringResource(R.string.usdt__deposit_fee_note), color = Colors.White64)
            VerticalSpacer(24.dp)
            EstimateRow(
                stringResource(R.string.usdt__receive_fee),
                if (address.amount >= address.estimatedReceived) {
                    usdtFormatAmount(
                        address.amount - address.estimatedReceived
                    )
                } else {
                    "—"
                }
            )
            EstimateRow(stringResource(R.string.usdt__receive_estimate), usdtFormatAmount(address.estimatedReceived))
            FillHeight()
            PrimaryButton(stringResource(R.string.common__continue), onClick = {
                onPage(ReceivePage.ADDRESS)
            }, modifier = Modifier.testTag("UsdtReceiveFeesContinue"))
        }
        ReceivePage.ADDRESS -> {
            val atomic = runCatching { PaykitAmount.parse(PaykitAsset.USDT, amount).atomic }.getOrNull()
            val uri = if (atomic == null) wallet.receiveUri else wallet.receiveUri + "&uint256=$atomic"
            UsdtReceiveContent(
                address = state.address?.address ?: wallet.address,
                uri = state.address?.uri ?: uri,
                error = receiveError,
                network = state.network?.label ?: "Arbitrum One",
                onEdit = onRefresh,
                onNetwork = { onPage(ReceivePage.NETWORKS) },
            )
        }
    }
}

@Composable
private fun ColumnScope.DepositHistory(
    state: DepositUiState,
    refundAddress: String,
    hideBalance: Boolean,
    onRefundAddress: (String) -> Unit,
    onRefund: () -> Unit,
    onMore: () -> Unit,
    onDetail: (String) -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
    ) {
        BodyS(stringResource(R.string.usdt__deposit_status_note), color = Colors.White64)
        if (state.detail != null) {
            DepositDetail(state.detail, refundAddress, hideBalance, state.busy, onRefundAddress, onRefund)
        } else {
            if (state.deposits.isEmpty()) {
                BodyS(
                    stringResource(R.string.usdt__deposit_empty),
                    color = Colors.White64
                )
            }
            state.deposits.forEach { deposit ->
                ActivityRowSurface(onClick = { if (!state.busy) onDetail(deposit.id) }) {
                    Column(modifier = Modifier.weight(1f)) {
                        BodySSB(deposit.network.uppercase())
                        BodyS(stringResource(depositStatus(deposit.status, deposit.code)), color = Colors.White64)
                    }
                    BodySSB(if (deposit.asset == "USDT") deposit.amount.usdtText(hideBalance) else deposit.asset)
                }
            }
            if (state.nextOffset != null) {
                SecondaryButton(
                    stringResource(R.string.usdt__deposit_more),
                    onClick = onMore,
                    enabled = !state.busy
                )
            }
        }
        state.error?.let { BodyS(stringResource(it), color = Colors.Brand) }
        state.message?.let { BodyS(stringResource(it), color = Colors.White64) }
    }
    VerticalSpacer(16.dp)
    SecondaryButton(
        stringResource(R.string.usdt__deposit_refresh),
        onClick = onHistory,
        enabled = !state.busy,
        isLoading = state.busy
    )
}

private enum class ReceivePage { ADDRESS, NETWORKS, AMOUNT, FEES }

@Composable
private fun ColumnScope.NetworkSelector(
    state: DepositUiState,
    onNetwork: (UsdtDepositNetwork?) -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
    ) {
        NetworkRow("Arbitrum One", state.network == null, onClick = { onNetwork(null) }, isDefault = true)
        state.networks.forEach { item ->
            NetworkRow(item.label, state.network == item, onClick = { onNetwork(item) })
        }
        BodyS(
            stringResource(R.string.usdt__receive_network_note),
            color = Colors.White64,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        UsdtCoinIllustration(modifier = Modifier.align(Alignment.CenterHorizontally))
        if (Env.usdtDepositsUrl != null) {
            TertiaryButton(
                stringResource(R.string.usdt__deposit_history),
                onClick = onHistory,
                modifier = Modifier.testTag("UsdtDepositHistory")
            )
        }
        state.error?.let { BodyS(stringResource(it), color = Colors.White64) }
    }
}

@Composable
private fun NetworkRow(title: String, selected: Boolean, onClick: () -> Unit, isDefault: Boolean = false) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().background(
            Colors.Gray6,
            RoundedCornerShape(16.dp)
        ).clickableAlpha(onClick = onClick).padding(16.dp).testTag("UsdtNetwork-$title")
    ) {
        Image(painterResource(R.drawable.tether), null, modifier = Modifier.size(40.dp))
        BodyMSB(title)
        if (isDefault) BodyS(stringResource(R.string.common__default), color = Colors.White64)
        FillWidth()
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                null,
                tint = Colors.Usdt,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun EstimateRow(title: String, value: String) {
    SendCell(caption = title, dividerSpacing = 24.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            BodyMSB("± $", color = Colors.White64)
            BodyMSB(value)
        }
    }
    VerticalSpacer(24.dp)
}

@Composable
private fun DepositDetail(
    detail: UsdtDepositDetail,
    address: String,
    hideBalance: Boolean,
    busy: Boolean,
    onAddress: (String) -> Unit,
    onRefund: () -> Unit
) {
    val deposit = detail.deposit
    val status = detail.order?.status ?: deposit.status
    val code = detail.statusCode
    if (deposit.asset == "USDT") {
        UsdtAmountHeader(
            deposit.amount?.let { usdtFormatAmount(it) } ?: "—",
            deposit.network.uppercase(),
            hideBalance = hideBalance
        )
    } else {
        BodySSB(deposit.asset + " · " + deposit.network.uppercase())
    }
    DepositField(R.string.wallet__activity_status, stringResource(detail.statusResource()))
    DepositField(R.string.usdt__deposit_reference, deposit.id)
    DepositField(R.string.wallet__activity_tx_id, deposit.sourceTx)
    code?.let { BodyS(it, color = Colors.White64) }
    DepositSettlement(detail, hideBalance)
    if (deposit.asset != "USDT" || deposit.network.depositNetwork() == null) {
        BodyS(stringResource(R.string.usdt__deposit_wrong_asset), color = Colors.White64)
    } else if (status !in listOf("completed", "refunded", "refunding", "refund_requested")) {
        BodyS(stringResource(R.string.usdt__deposit_refund_note), color = Colors.White64)
        TextInput(
            value = address,
            onValueChange = { if (!busy) onAddress(it) },
            placeholder = stringResource(R.string.usdt__deposit_refund_address),
            modifier = Modifier.testTag("UsdtRefundAddress")
        )
        SecondaryButton(
            stringResource(R.string.usdt__deposit_refund),
            onClick = onRefund,
            enabled = !busy && address.isNotBlank(),
            isLoading = busy,
            modifier = Modifier.testTag("UsdtDepositRefund")
        )
    }
    UsdtSupportActions(detail.supportDetails, enabled = !busy)
}

@Composable
private fun DepositSettlement(detail: UsdtDepositDetail, hideBalance: Boolean) {
    val order = detail.order?.takeUnless { it.status == "refunded" }
    val deposit = detail.deposit
    order?.amountOut?.let { DepositField(R.string.usdt__deposit_batch, it.usdtText(hideBalance)) }
    order?.destinationTx?.let {
        SendCell(caption = stringResource(R.string.wallet__activity_tx_id) + " · Arbitrum One") {
            SelectionContainer { BodySSB(it) }
        }
    }
    (detail.order?.refundTx ?: deposit.refundTx)?.let { DepositField(R.string.usdt__deposit_refund, it) }
    val sent = order?.amountIn
    val received = order?.amountOut
    if (sent != null && received != null && sent >= received) {
        DepositField(R.string.usdt__deposit_cost, (sent - received).usdtText(hideBalance))
    }
}

@Composable
private fun DepositField(title: Int, value: String) {
    SendCell(caption = stringResource(title)) { SelectionContainer { BodySSB(value) } }
}

private val UsdtDepositNetwork.label: String
    get() = when (this) {
        UsdtDepositNetwork.ETHEREUM -> "Ethereum"
        UsdtDepositNetwork.SOLANA -> "Solana"
        UsdtDepositNetwork.POLYGON -> "Polygon"
        UsdtDepositNetwork.BSC -> "BNB Smart Chain"
        UsdtDepositNetwork.BASE -> "Base"
        UsdtDepositNetwork.TRON -> "Tron"
    }
internal fun String.depositNetwork(): UsdtDepositNetwork? =
    UsdtDepositNetwork.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }
private fun ULong?.usdtText(hidden: Boolean) = (
    if (hidden) {
        "•••••"
    } else {
        this?.let {
            usdtFormatAmount(it)
        } ?: "—"
    }
    ) + " USDT"
internal fun String.usdCents(locale: Locale = Locale.getDefault()): String = toULongOrNull()?.let {
    val whole = NumberFormat.getIntegerInstance(locale).format((it / 100u).toLong())
    "$$whole.${(it % 100u).toString().padStart(2, '0')}"
} ?: "—"
private fun depositStatus(status: String, code: String?): Int = when {
    code != null -> R.string.usdt__deposit_needs_attention
    status == "completed" -> R.string.usdt__confirmed
    status == "refunded" -> R.string.usdt__deposit_refunded
    status in listOf("refunding", "refund_requested") -> R.string.usdt__deposit_refunding
    status in listOf("pending", "processing") -> R.string.usdt__pending
    else -> R.string.usdt__deposit_needs_attention
}

internal val UsdtDepositDetail.statusCode: String?
    get() = if (order != null) order?.code else deposit.code

internal fun UsdtDepositDetail.statusResource(): Int = depositStatus(order?.status ?: deposit.status, statusCode)
