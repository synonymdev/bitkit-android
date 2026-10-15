package to.bitkit.ui.screens.wallets.usdt

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.UsdtDepositDetail
import com.synonym.bitkitcore.UsdtDepositNetwork
import com.synonym.bitkitcore.usdtFormatAmount
import kotlinx.collections.immutable.ImmutableList
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.models.PaykitAmount
import to.bitkit.models.PaykitAsset
import to.bitkit.repositories.UsdtWalletState
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.BodyM
import to.bitkit.ui.components.BodyMSB
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SendCell
import to.bitkit.ui.components.UsdtAmountHeader
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.usdtOverviewAmount
import to.bitkit.ui.scaffold.SheetTopBar
import to.bitkit.ui.screens.wallets.activity.components.CustomTabRowWithSpacing
import to.bitkit.ui.screens.wallets.receive.ReceiveTab
import to.bitkit.ui.theme.Colors
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

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
    val walletUi by viewModel.state.collectAsStateWithLifecycle()
    val state by deposits.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(ReceivePage.ADDRESS) }
    var amount by remember { mutableStateOf("") }
    var draftAmount by remember { mutableStateOf("") }
    val app = appViewModel
    val back = {
        if (!state.busy) {
            when (page) {
                ReceivePage.FEES -> {
                    deposits.selectNetwork(state.network)
                    page = ReceivePage.AMOUNT
                }
                ReceivePage.AMOUNT -> {
                    draftAmount = amount
                    if (state.network != null && state.address == null) deposits.selectNetwork(null)
                    page = ReceivePage.ADDRESS
                }
                ReceivePage.NETWORKS -> page = ReceivePage.ADDRESS
                ReceivePage.ADDRESS -> onBack()
            }
        }
    }
    LaunchedEffect(state.busy) { onBlockingChange(state.busy) }
    DisposableEffect(Unit) { onDispose { onBlockingChange(false) } }
    LaunchedEffect(state.address) { if (state.address != null) page = ReceivePage.FEES }
    UsdtRefreshEffect(viewModel, receiving = true, onReceived = { !state.busy })
    BackHandler(onBack = back)
    SheetTopBar(
        titleText = stringResource(
            when (page) {
                ReceivePage.ADDRESS -> R.string.usdt__receive_title
                ReceivePage.NETWORKS -> R.string.usdt__receive_network_title
                ReceivePage.AMOUNT -> R.string.usdt__receive_amount_title
                ReceivePage.FEES -> R.string.usdt__receive_fees_title
            }
        ),
        onBack = back.takeIf { page != ReceivePage.ADDRESS },
        action = if (page == ReceivePage.ADDRESS && onContacts != null) {
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
    VerticalSpacer(
        if (page == ReceivePage.NETWORKS || page == ReceivePage.AMOUNT) 8.dp else 16.dp
    )
    if (page == ReceivePage.ADDRESS) {
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
        amount = if (page == ReceivePage.AMOUNT) draftAmount else amount,
        onNetwork = {
            if (state.network != it) {
                amount = ""
                draftAmount = ""
                deposits.selectNetwork(it)
            }
            page = if (it == null || state.address != null && state.network == it) {
                ReceivePage.ADDRESS
            } else {
                ReceivePage.AMOUNT
            }
        },
        onAmount = {
            if (draftAmount != it) deposits.clearAmountError()
            draftAmount = it
        },
        onCreate = {
            if (state.network == null) {
                amount = draftAmount
                page = ReceivePage.ADDRESS
            } else {
                deposits.prepare(draftAmount)
            }
        },
        onHistory = { app?.navigateToUsdtDeposits() },
        onRefresh = {
            draftAmount = if (state.network == null) amount else draftAmount
            deposits.selectNetwork(state.network)
            page = ReceivePage.AMOUNT
        },
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
    onNetwork: (UsdtDepositNetwork?) -> Unit,
    onAmount: (String) -> Unit,
    onCreate: () -> Unit,
    onHistory: () -> Unit,
    onRefresh: () -> Unit,
) {
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
            UsdtAmountHeader(usdtFormatAmount(address.amount), displayAmount = usdtOverviewAmount(address.amount))
            VerticalSpacer(24.dp)
            BodyM(stringResource(R.string.usdt__deposit_fee_note), color = Colors.White64)
            VerticalSpacer(24.dp)
            EstimateRow(
                stringResource(R.string.usdt__receive_fee),
                if (address.amount >= address.estimatedReceived) {
                    usdtOverviewAmount(address.amount - address.estimatedReceived)
                } else {
                    "—"
                }
            )
            EstimateRow(stringResource(R.string.usdt__receive_estimate), usdtOverviewAmount(address.estimatedReceived))
            FillHeight()
            PrimaryButton(stringResource(R.string.common__continue), onClick = {
                onPage(ReceivePage.ADDRESS)
            }, modifier = Modifier.testTag("UsdtReceiveFeesContinue"))
        }
        ReceivePage.ADDRESS -> {
            val atomic = runCatching { PaykitAmount.parseInput(PaykitAsset.USDT, amount).atomic }.getOrNull()
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
        UsdtNetworkRow("Arbitrum One", state.network == null, onClick = { onNetwork(null) })
        state.networks.forEach { item ->
            UsdtNetworkRow(item.label, state.network == item, onClick = { onNetwork(item) })
        }
        BodyS(
            stringResource(R.string.usdt__receive_network_note),
            color = Colors.White64,
            letterSpacing = 0.sp,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        UsdtCoinIllustration(modifier = Modifier.align(Alignment.CenterHorizontally))
        state.error?.let { BodyS(stringResource(it), color = Colors.White64) }
    }
    if (Env.usdtDepositsUrl != null) {
        VerticalSpacer(16.dp)
        PrimaryButton(
            stringResource(R.string.usdt__deposit_history),
            onClick = onHistory,
            modifier = Modifier.testTag("UsdtDepositHistory")
        )
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

internal val UsdtDepositNetwork.label: String
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
internal fun String.usdCents(locale: Locale = Locale.getDefault()): String = toULongOrNull()?.let {
    val whole = NumberFormat.getIntegerInstance(locale).format((it / 100u).toLong())
    "$$whole${DecimalFormatSymbols.getInstance(locale).decimalSeparator}${(it % 100u).toString().padStart(2, '0')}"
} ?: "—"
internal fun depositStatus(status: String, code: String?): Int = when (status) {
    "completed" -> R.string.usdt__confirmed
    "refunded" -> R.string.usdt__deposit_refunded
    "refunding", "refund_requested" -> if (code == null || code == "standing_refund_pending") {
        R.string.usdt__deposit_refunding
    } else {
        R.string.usdt__deposit_needs_attention
    }
    "pending", "processing", "funded", "confirming", "swapping", "bridging", "delivering" -> {
        if (code == null) R.string.usdt__pending else R.string.usdt__deposit_needs_attention
    }
    else -> R.string.usdt__deposit_needs_attention
}

internal val UsdtDepositDetail.statusCode: String?
    get() = if (order != null) order?.code else deposit.code

internal fun UsdtDepositDetail.statusResource(): Int = depositStatus(order?.status ?: deposit.status, statusCode)
