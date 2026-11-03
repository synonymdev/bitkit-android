package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.synonym.bitkitcore.UsdtDeposit
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtDepositNetwork
import com.synonym.bitkitcore.UsdtDestination
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.TextInput
import to.bitkit.ui.components.usdtExactAmount
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.AppAlertDialog
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.DrawerNavIcon
import to.bitkit.ui.screens.wallets.activity.components.ActivityRowSurface
import to.bitkit.ui.screens.wallets.activity.components.AmountViewContent
import to.bitkit.ui.screens.wallets.activity.components.CircularIcon
import to.bitkit.ui.shared.UiConstants
import to.bitkit.ui.theme.Colors
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun UsdtDepositsScreen(
    onBack: () -> Unit,
    deposits: UsdtDepositViewModel = hiltViewModel(),
    wallet: UsdtViewModel = hiltViewModel(),
) {
    val state by deposits.state.collectAsStateWithLifecycle()
    val settings by wallet.settings.collectAsStateWithLifecycle()
    val walletUi by wallet.state.collectAsStateWithLifecycle()
    var address by rememberSaveable(state.detail?.deposit?.id) { mutableStateOf("") }
    var approval by remember { mutableStateOf<RefundApproval?>(null) }
    var confirmation by remember { mutableStateOf(false) }
    var authentication by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val back = {
        if (authentication) {
            authentication = false
            approval = null
        } else if (!state.busy && approval == null) {
            if (state.detail != null) deposits.back() else onBack()
        }
    }
    val refund = {
        approval?.let { deposits.refund(it.id, it.offset, it.address, it.network) }
        approval = null
    }
    LaunchedEffect(deposits) { deposits.history() }
    DepositPolling(state.detail, lifecycle, approval != null, deposits::refreshDetail)
    BackHandler(onBack = back)
    if (authentication) {
        UsdtAuthentication(
            useBiometrics = settings.isBiometricEnabled,
            onCancel = {
                authentication = false
                approval = null
            },
            onVerified = {
                authentication = false
                refund()
            },
        )
    } else {
        UsdtDepositsContent(
            state = state.copy(busy = state.busy || approval != null, error = state.error ?: walletUi.error),
            address = address,
            hideBalance = settings.hideBalance,
            onBack = back,
            onAddress = {
                address = it
                deposits.clearError()
            },
            onDetail = { scope.launch { deposits.detail(it) } },
            onHistory = { deposits.back() },
            onMore = deposits::more,
            onRefresh = { deposits.refresh() },
            onRefund = {
                refundApproval(state, address)?.let {
                    approval = it
                    confirmation = true
                }
            },
        )
    }
    if (confirmation) {
        approval?.let { approved ->
            UsdtRefundDialog(
                amount = approved.amount,
                hideBalance = settings.hideBalance,
                network = approved.network,
                address = approved.address,
                onCancel = {
                    confirmation = false
                    approval = null
                },
                onConfirm = {
                    confirmation = false
                    scope.launch {
                        try {
                            authorizeRefund(
                                wallet = wallet,
                                isCurrent = { approval == approved },
                                onAuthenticate = { authentication = true },
                                onRefund = refund,
                            )
                        } finally {
                            if (!authentication && approval == approved) approval = null
                        }
                    }
                },
            )
        }
    }
}

private suspend fun authorizeRefund(
    wallet: UsdtViewModel,
    isCurrent: () -> Boolean,
    onAuthenticate: () -> Unit,
    onRefund: () -> Unit,
) {
    wallet.readPaymentSettings {
        if (!isCurrent()) return@readPaymentSettings
        if (it.isPinEnabled && it.isPinForPaymentsEnabled) onAuthenticate() else onRefund()
    }
}

@Composable
private fun DepositPolling(
    detail: UsdtDepositDetail?,
    lifecycle: Lifecycle,
    paused: Boolean,
    refresh: suspend (String) -> Unit
) {
    val id = detail?.takeIf { it.providerStatus !in listOf("completed", "refunded") }?.deposit?.id
    LaunchedEffect(id, lifecycle, paused) {
        if (id == null || paused) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(10.seconds)
                refresh(id)
            }
        }
    }
}

private fun refundApproval(state: DepositUiState, address: String): RefundApproval? {
    val detail = state.detail ?: return null
    if (state.busy || !detail.canRefund) return null
    val network = detail.deposit.network.depositNetwork() ?: return null
    return RefundApproval(detail.deposit.id, state.offset, address.trim(), network, detail.deposit.amount)
}

private data class RefundApproval(
    val id: String,
    val offset: UInt,
    val address: String,
    val network: UsdtDepositNetwork,
    val amount: ULong?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsdtDepositsContent(
    state: DepositUiState,
    address: String,
    hideBalance: Boolean,
    onBack: () -> Unit,
    onAddress: (String) -> Unit,
    onDetail: (String) -> Unit,
    onHistory: () -> Unit,
    onMore: () -> Unit,
    onRefresh: () -> Unit,
    onRefund: () -> Unit,
) {
    val detail = state.detail
    var showDetails by rememberSaveable(detail?.deposit?.id) { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(detail?.deposit?.id, showDetails) { scrollState.scrollTo(0) }
    Column(modifier = Modifier.fillMaxSize().background(Colors.Black).imePadding()) {
        AppTopBar(
            titleText = stringResource(
                if (detail == null) R.string.usdt__deposit_history else R.string.usdt__receiving_title
            ),
            onBackClick = onBack,
            titleStyle = UsdtDetailStyles.Title,
            actions = { DrawerNavIcon() },
        )
        PullToRefreshBox(
            isRefreshing = state.busy,
            onRefresh = { if (!state.busy) onRefresh() },
            modifier = Modifier.weight(1f)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().verticalScroll(scrollState)
                    .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)
            ) {
                if (detail == null) {
                    UsdtDepositHistory(state, hideBalance, onDetail, onMore)
                } else {
                    if (detail.deposit.asset == "USDT") {
                        UsdtActivityAmountHeader(
                            amount = detail.deposit.amount,
                            isIncoming = true,
                            hideBalance = hideBalance,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    } else {
                        BodyMSB(detail.deposit.asset)
                    }
                    if (showDetails) {
                        UsdtDepositTechnicalDetails(detail, address, state.busy, state.refundError, onAddress)
                    } else {
                        UsdtDepositSummary(detail)
                    }
                    UsdtDepositDetailActions(
                        showDetails = showDetails,
                        canRefund = detail.canRefund,
                        busy = state.busy,
                        address = address,
                        onRefund = onRefund,
                        onToggleDetails = {
                            focusManager.clearFocus()
                            showDetails = !showDetails
                        },
                    )
                    if (showDetails) {
                        if (detail.canRefund && detail.deposit.network.depositNetwork() == UsdtDepositNetwork.TRON) {
                            BodyS(stringResource(R.string.usdt__deposit_tron_refund_note), color = Colors.White64)
                        }
                        DepositSettlement(detail, hideBalance)
                        UsdtSupportActions(
                            detail.supportDetails(stringResource(detail.statusResource())),
                            enabled = !state.busy,
                        )
                    }
                }
                state.error?.let { BodyS(stringResource(it), color = Colors.Brand) }
                state.message?.let { BodyS(stringResource(it), color = Colors.White64) }
            }
        }
        if (detail != null) {
            PrimaryButton(
                text = stringResource(R.string.usdt__deposit_history),
                textStyle = UsdtDetailStyles.Button,
                onClick = onHistory,
                enabled = !state.busy,
                modifier = Modifier.padding(16.dp).testTag("UsdtDepositHistory")
            )
        }
    }
}

@Composable
private fun UsdtDepositDetailActions(
    showDetails: Boolean,
    canRefund: Boolean,
    busy: Boolean,
    address: String,
    onRefund: () -> Unit,
    onToggleDetails: () -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (showDetails && canRefund) {
            PrimaryButton(
                text = stringResource(R.string.usdt__deposit_refund_action),
                onClick = onRefund,
                enabled = !busy && address.isNotBlank(),
                isLoading = busy,
                size = ButtonSize.Small,
                textStyle = UsdtDetailStyles.SmallButton,
                icon = {
                    Icon(
                        painterResource(R.drawable.ic_coins),
                        null,
                        tint = Colors.Usdt,
                        modifier = Modifier.size(16.dp)
                    )
                },
                modifier = Modifier.weight(1f).testTag("UsdtDepositRefund")
            )
        }
        PrimaryButton(
            text = stringResource(
                if (showDetails) R.string.usdt__hide_details else R.string.wallet__send_details
            ),
            onClick = onToggleDetails,
            size = ButtonSize.Small,
            textStyle = UsdtDetailStyles.SmallButton,
            icon = {
                Icon(
                    painterResource(
                        if (showDetails) R.drawable.ic_eye_slash else R.drawable.ic_git_branch
                    ),
                    null,
                    tint = Colors.Usdt,
                    modifier = Modifier.size(16.dp)
                )
            },
            modifier = Modifier.weight(1f).testTag("UsdtDepositDetails")
        )
    }
}

@Composable
private fun ColumnScope.UsdtDepositHistory(
    state: DepositUiState,
    hideBalance: Boolean,
    onDetail: (String) -> Unit,
    onMore: () -> Unit,
) {
    UsdtDetailField(caption = stringResource(R.string.usdt__deposit_provider_status)) {
        BodySSB(stringResource(R.string.usdt__deposit_status_note), style = UsdtDetailStyles.Body)
    }
    if (state.deposits.isEmpty() && !state.busy) {
        BodyS(stringResource(R.string.usdt__deposit_empty), color = Colors.White64)
    }
    state.deposits.forEach { deposit ->
        UsdtDepositRow(deposit, hideBalance, onClick = { if (!state.busy) onDetail(deposit.id) })
    }
    if (state.nextOffset != null) {
        SecondaryButton(stringResource(R.string.usdt__deposit_more), onMore, enabled = !state.busy)
    }
}

@Composable
private fun UsdtDepositRow(deposit: UsdtDeposit, hideBalance: Boolean, onClick: () -> Unit) {
    val status = depositStatus(deposit.status, deposit.code)
    val confirmed = status == R.string.usdt__confirmed || status == R.string.usdt__deposit_refunded
    val color = if (confirmed) Colors.Usdt else Colors.Yellow
    ActivityRowSurface(onClick = onClick, modifier = Modifier.testTag("UsdtDepositRow-" + deposit.id)) {
        CircularIcon(
            icon = painterResource(if (confirmed) R.drawable.ic_check_circle else R.drawable.ic_hourglass_simple),
            iconColor = color,
            backgroundColor = color.copy(alpha = 0.16f),
            size = 40.dp,
        )
        HorizontalSpacer(16.dp)
        Column(modifier = Modifier.weight(1f)) {
            BodyMSB(
                deposit.network.depositNetwork()?.label ?: deposit.network,
                maxLines = 1,
                style = UsdtDetailStyles.HistoryTitle
            )
            CaptionB(
                stringResource(status),
                color = Colors.White64,
                maxLines = 1,
                style = UsdtDetailStyles.HistorySubtitle
            )
        }
        HorizontalSpacer(16.dp)
        AmountViewContent(
            title = if (deposit.asset == "USDT") {
                deposit.amount?.let { usdtOverviewAmount(it) + " USDT" } ?: "—"
            } else {
                deposit.asset
            },
            titlePrefix = "",
            subtitle = if (deposit.asset == "USDT") {
                deposit.amount?.let { "$" + usdtOverviewAmount(it) } ?: "—"
            } else {
                "—"
            },
            hideBalance = hideBalance,
            titleStyle = UsdtDetailStyles.HistoryTitle,
            subtitleStyle = UsdtDetailStyles.HistorySubtitle,
            verticalSpacing = 0.dp,
        )
    }
}

@Composable
private fun UsdtDepositSummary(detail: UsdtDepositDetail) {
    val settled = detail.providerStatus in listOf("completed", "refunded")
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        UsdtDetailCell(
            title = stringResource(R.string.wallet__activity_status),
            value = stringResource(detail.statusResource()),
            icon = if (settled) {
                R.drawable.ic_check_circle
            } else {
                R.drawable.ic_hourglass_simple
            },
            textStyle = UsdtDetailStyles.Body,
            valueColor = if (settled) Colors.Usdt else Colors.Yellow,
            iconColor = if (settled) Colors.Usdt else Colors.Yellow,
            modifier = Modifier.weight(1f)
        )
        UsdtDetailCell(
            title = stringResource(R.string.usdt__receiving_network),
            value = UsdtDestination.ARBITRUM.label,
            icon = R.drawable.usdt_network,
            modifier = Modifier.weight(1f)
        )
    }
    UsdtDetailCell(
        title = stringResource(R.string.usdt__sender_network),
        value = detail.deposit.network.depositNetwork()?.label ?: detail.deposit.network,
        icon = R.drawable.usdt_network,
    )
    UsdtDetailField(caption = stringResource(R.string.usdt__deposit_provider_status)) {
        BodySSB(stringResource(R.string.usdt__deposit_status_note), style = UsdtDetailStyles.Body)
    }
}

@Composable
private fun UsdtDepositTechnicalDetails(
    detail: UsdtDepositDetail,
    address: String,
    busy: Boolean,
    refundError: Int?,
    onAddress: (String) -> Unit,
) {
    DepositField(R.string.wallet__activity_tx_id, detail.deposit.sourceTx)
    DepositField(R.string.usdt__deposit_reference, detail.deposit.id)
    if (detail.deposit.asset != "USDT" || detail.deposit.network.depositNetwork() == null) {
        BodyS(stringResource(R.string.usdt__deposit_wrong_asset), color = Colors.White64)
    } else if (detail.canRefund) {
        val network = detail.deposit.network.depositNetwork()?.refundLabel.orEmpty()
        UsdtDetailField(caption = stringResource(R.string.usdt__deposit_refund_title)) {
            BodySSB(
                stringResource(R.string.usdt__deposit_refund_instructions).replace("{network}", network),
                style = UsdtDetailStyles.Body
            )
        }
        TextInput(
            value = address,
            onValueChange = { if (!busy) onAddress(it) },
            enabled = !busy,
            singleLine = true,
            textStyle = UsdtDetailStyles.Body,
            placeholderColor = Colors.White.copy(alpha = 0.5f),
            placeholder = stringResource(R.string.usdt__deposit_refund_address).replace("{network}", network),
            modifier = Modifier.fillMaxWidth().testTag("UsdtRefundAddress")
        )
        refundError?.let {
            BodyS(stringResource(it), color = Colors.Brand, modifier = Modifier.testTag("UsdtRefundError"))
        }
    } else {
        UsdtDepositSummary(detail)
    }
    if (detail.statusResource() == R.string.usdt__deposit_needs_attention) {
        BodyS(stringResource(R.string.usdt__deposit_attention), color = Colors.White64)
    }
}

@Composable
private fun DepositSettlement(detail: UsdtDepositDetail, hideBalance: Boolean) {
    val order = detail.order?.takeUnless { it.status == "refunded" }
    order?.amountOut?.let { DepositField(R.string.usdt__deposit_batch, it.usdtText(hideBalance)) }
    order?.destinationTx?.let {
        UsdtDetailField(caption = stringResource(R.string.wallet__activity_tx_id) + " · Arbitrum One") {
            SelectionContainer { BodySSB(it, style = UsdtDetailStyles.Body) }
        }
    }
    (detail.order?.refundTx ?: detail.deposit.refundTx)?.let { DepositField(R.string.usdt__refund_tx, it) }
    val sent = order?.amountIn
    val received = order?.amountOut
    if (sent != null && received != null && sent >= received) {
        DepositField(R.string.usdt__deposit_cost, (sent - received).usdtText(hideBalance))
    }
}

@Composable
private fun DepositField(title: Int, value: String) {
    UsdtDetailField(caption = stringResource(title)) {
        SelectionContainer { BodySSB(value, style = UsdtDetailStyles.Body) }
    }
}

@Composable
internal fun UsdtRefundDialog(
    amount: ULong?,
    hideBalance: Boolean,
    network: UsdtDepositNetwork,
    address: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AppAlertDialog(
        title = stringResource(R.string.usdt__deposit_refund_confirm),
        text = stringResource(R.string.usdt__deposit_refund_confirmation)
            .replace("{amount}", amount.usdtText(hideBalance))
            .replace("{network}", if (network == UsdtDepositNetwork.BSC) "${network.label} (BSC)" else network.label)
            .replace("{address}", address)
            .replace("{source}", network.refundLabel),
        confirmText = stringResource(R.string.usdt__deposit_refund),
        dismissText = stringResource(R.string.common__cancel),
        onConfirm = onConfirm,
        onDismiss = onCancel,
        confirmTextColor = Colors.Brand,
        dismissTextColor = Colors.Brand,
        confirmTestTag = "UsdtRefundConfirm",
        dismissTestTag = "UsdtRefundCancel",
    )
}

private val UsdtDepositNetwork.refundLabel get() = if (this == UsdtDepositNetwork.BSC) "BSC" else label
internal val UsdtDepositDetail.providerStatus get() = order?.status ?: deposit.status
internal val UsdtDepositDetail.canRefund get() = deposit.asset == "USDT" && deposit.network.depositNetwork() != null &&
    providerStatus !in listOf("completed", "refunded", "refunding", "refund_requested")

private fun ULong?.usdtText(hidden: Boolean) = (
    if (hidden) UiConstants.HIDE_BALANCE_SHORT else this?.let { usdtExactAmount(it) } ?: "—"
    ) + " USDT"
