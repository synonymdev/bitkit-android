package to.bitkit.ui.screens.transfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.BtBolt11InvoiceState
import com.synonym.bitkitcore.BtOrderState
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.BtPaymentState
import com.synonym.bitkitcore.BtPaymentState2
import com.synonym.bitkitcore.IBtBolt11Invoice
import com.synonym.bitkitcore.IBtOnchainTransaction
import com.synonym.bitkitcore.IBtOnchainTransactions
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IBtPayment
import com.synonym.bitkitcore.ILspNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.R
import to.bitkit.ui.components.ButtonSize
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.Display
import to.bitkit.ui.components.FeeInfo
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SwipeToConfirm
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.withAccent
import to.bitkit.viewmodels.TransferViewModel

@Composable
fun SpendingConfirmScreen(
    viewModel: TransferViewModel,
    onBackClick: () -> Unit = {},
    onCloseClick: () -> Unit = {},
    onLearnMoreClick: () -> Unit = {},
    onAdvancedClick: () -> Unit = {},
    onConfirm: () -> Unit = {},
) {
    val state by viewModel.spendingUiState.collectAsStateWithLifecycle()
    val order = state.order ?: return
    val isAdvanced = state.isAdvanced

    Content(
        onBackClick = onBackClick,
        onCloseClick = onCloseClick,
        onLearnMoreClick = onLearnMoreClick,
        onAdvancedClick = onAdvancedClick,
        onConfirm = onConfirm,
        onUseDefaultLspBalanceClick = { viewModel.onUseDefaultLspBalanceClick() },
        onTransferToSpendingConfirm = { order -> order },
        order = order,
        isAdvanced = isAdvanced
    )
}

@Composable
private fun Content(
    onBackClick: () -> Unit,
    onCloseClick: () -> Unit,
    onLearnMoreClick: () -> Unit,
    onAdvancedClick: () -> Unit,
    onConfirm: () -> Unit,
    onUseDefaultLspBalanceClick: () -> Unit,
    onTransferToSpendingConfirm: (IBtOrder) -> Unit,
    order: IBtOrder,
    isAdvanced: Boolean,
) {
    val scope = rememberCoroutineScope()

    ScreenColumn {
        AppTopBar(
            titleText = stringResource(R.string.lightning__transfer__nav_title),
            onBackClick = onBackClick,
            actions = { CloseNavIcon(onCloseClick) },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            if (!isAdvanced) {
                Image(
                    painter = painterResource(id = R.drawable.coin_stack_x),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 60.dp)
                        .align(alignment = Alignment.BottomCenter)
                        .padding(bottom = 76.dp)
                )
            }

            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                val clientBalance = order.clientBalanceSat
                val networkFee = order.networkFeeSat
                val serviceFee = order.serviceFeeSat
                val totalFee = order.feeSat
                val lspBalance = order.lspBalanceSat

                VerticalSpacer(32.dp)
                Display(
                    text = stringResource(R.string.lightning__transfer__confirm)
                        .withAccent(accentColor = Colors.Purple)
                )
                VerticalSpacer(8.dp)

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.height(IntrinsicSize.Min)
                ) {
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__network_fee),
                        amount = networkFee.toLong(),
                    )
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__lsp_fee),
                        amount = serviceFee.toLong(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.height(IntrinsicSize.Min)
                ) {
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__amount),
                        amount = clientBalance.toLong(),
                    )
                    FeeInfo(
                        label = stringResource(R.string.lightning__spending_confirm__total),
                        amount = totalFee.toLong(),
                    )
                }

                if (isAdvanced) {
                    VerticalSpacer(16.dp)
                    LightningChannel(
                        capacity = (clientBalance + lspBalance).toLong(),
                        localBalance = clientBalance.toLong(),
                        remoteBalance = lspBalance.toLong(),
                        status = ChannelStatusUi.OPEN,
                        showLabels = true,
                    )
                }

                VerticalSpacer(16.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrimaryButton(
                        text = stringResource(R.string.common__learn_more),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = onLearnMoreClick,
                    )
                    PrimaryButton(
                        text = stringResource(
                            if (isAdvanced) R.string.lightning__spending_confirm__default else R.string.common__advanced
                        ),
                        size = ButtonSize.Small,
                        fullWidth = false,
                        onClick = {
                            if (isAdvanced) {
                                onUseDefaultLspBalanceClick()
                            } else {
                                onAdvancedClick()
                            }
                        },
                    )
                }
                VerticalSpacer(16.dp)

                FillHeight()

                var isLoading by remember { mutableStateOf(false) }
                SwipeToConfirm(
                    text = stringResource(R.string.lightning__transfer__swipe),
                    loading = isLoading,
                    color = Colors.Purple,
                    onConfirm = {
                        scope.launch {
                            isLoading = true
                            delay(300)
                            onTransferToSpendingConfirm(order)
                            onConfirm()
                        }
                    }
                )
                VerticalSpacer(16.dp)
            }
        }
    }

}

@Preview(showSystemUi = true, showBackground = true, name = "Normal screen - Default")
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onCloseClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onConfirm = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            order = IBtOrder(
                id = "order_7e6f3b7c-486a-4f5a-8b1e-2c9d7f0a8b9d",
                state = BtOrderState.CREATED,
                state2 = BtOrderState2.CREATED,
                feeSat = 1000UL,
                networkFeeSat = 250UL,
                serviceFeeSat = 750UL,
                lspBalanceSat = 2000000UL,
                clientBalanceSat = 500000UL,
                zeroConf = false,
                zeroReserve = true,
                clientNodeId = null,
                channelExpiryWeeks = 8u,
                channelExpiresAt = "2025-09-22T08:29:03Z",
                orderExpiresAt = "2025-07-29T08:29:03Z",
                channel = null,
                lspNode = ILspNode(
                    alias = "Bitkit LSP",
                    pubkey = "02f12451995802149b1855a7948305763328e9304337b51e45e7f1b637956424e8",
                    connectionStrings = listOf("mock@127.0.0.1:9735"),
                    readonly = null
                ),
                lnurl = null,
                payment = IBtPayment(
                    state = BtPaymentState.CREATED,
                    state2 = BtPaymentState2.CREATED,
                    paidSat = 0UL,
                    bolt11Invoice = IBtBolt11Invoice(
                        request = "lnmock",
                        state = BtBolt11InvoiceState.PENDING,
                        expiresAt = "2025-07-28T12:00:00Z",
                        updatedAt = "2025-07-28T08:30:00Z"
                    ),
                    onchain = IBtOnchainTransactions(
                        address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                        confirmedSat = 0UL,
                        requiredConfirmations = 1u,
                        transactions = listOf(
                            IBtOnchainTransaction(
                                amountSat = 50000UL,
                                txId = "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16",
                                vout = 0u,
                                blockHeight = null,
                                blockConfirmationCount = 0u,
                                feeRateSatPerVbyte = 12.5,
                                confirmed = false,
                                suspicious0ConfReason = ""
                            )
                        )
                    ),
                    isManuallyPaid = null,
                    manualRefunds = null
                ),
                couponCode = null,
                source = null,
                discount = null,
                updatedAt = "2025-07-28T08:29:03Z",
                createdAt = "2025-07-28T08:29:03Z"
            ),
            isAdvanced = false
        )
    }
}

@Preview(showSystemUi = true, showBackground = true, name = "Normal screen - Advanced")
@Composable
private fun Preview2() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onCloseClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onConfirm = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            order = IBtOrder(
                id = "order_7e6f3b7c-486a-4f5a-8b1e-2c9d7f0a8b9d",
                state = BtOrderState.CREATED,
                state2 = BtOrderState2.CREATED,
                feeSat = 1000UL,
                networkFeeSat = 250UL,
                serviceFeeSat = 750UL,
                lspBalanceSat = 2000000UL,
                clientBalanceSat = 500000UL,
                zeroConf = false,
                zeroReserve = true,
                clientNodeId = null,
                channelExpiryWeeks = 8u,
                channelExpiresAt = "2025-09-22T08:29:03Z",
                orderExpiresAt = "2025-07-29T08:29:03Z",
                channel = null,
                lspNode = ILspNode(
                    alias = "Bitkit LSP",
                    pubkey = "02f12451995802149b1855a7948305763328e9304337b51e45e7f1b637956424e8",
                    connectionStrings = listOf("mock@127.0.0.1:9735"),
                    readonly = null
                ),
                lnurl = null,
                payment = IBtPayment(
                    state = BtPaymentState.CREATED,
                    state2 = BtPaymentState2.CREATED,
                    paidSat = 0UL,
                    bolt11Invoice = IBtBolt11Invoice(
                        request = "lnmock",
                        state = BtBolt11InvoiceState.PENDING,
                        expiresAt = "2025-07-28T12:00:00Z",
                        updatedAt = "2025-07-28T08:30:00Z"
                    ),
                    onchain = IBtOnchainTransactions(
                        address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                        confirmedSat = 0UL,
                        requiredConfirmations = 1u,
                        transactions = listOf(
                            IBtOnchainTransaction(
                                amountSat = 50000UL,
                                txId = "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16",
                                vout = 0u,
                                blockHeight = null,
                                blockConfirmationCount = 0u,
                                feeRateSatPerVbyte = 12.5,
                                confirmed = false,
                                suspicious0ConfReason = ""
                            )
                        )
                    ),
                    isManuallyPaid = null,
                    manualRefunds = null
                ),
                couponCode = null,
                source = null,
                discount = null,
                updatedAt = "2025-07-28T08:29:03Z",
                createdAt = "2025-07-28T08:29:03Z"
            ),
            isAdvanced = true
        )
    }
}

@Preview(showSystemUi = true, showBackground = true, heightDp = 700, name = "Small screen - Normal")
@Composable
private fun Preview3() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onCloseClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onConfirm = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            order = IBtOrder(
                id = "order_7e6f3b7c-486a-4f5a-8b1e-2c9d7f0a8b9d",
                state = BtOrderState.CREATED,
                state2 = BtOrderState2.CREATED,
                feeSat = 1000UL,
                networkFeeSat = 250UL,
                serviceFeeSat = 750UL,
                lspBalanceSat = 2000000UL,
                clientBalanceSat = 500000UL,
                zeroConf = false,
                zeroReserve = true,
                clientNodeId = null,
                channelExpiryWeeks = 8u,
                channelExpiresAt = "2025-09-22T08:29:03Z",
                orderExpiresAt = "2025-07-29T08:29:03Z",
                channel = null,
                lspNode = ILspNode(
                    alias = "Bitkit LSP",
                    pubkey = "02f12451995802149b1855a7948305763328e9304337b51e45e7f1b637956424e8",
                    connectionStrings = listOf("mock@127.0.0.1:9735"),
                    readonly = null
                ),
                lnurl = null,
                payment = IBtPayment(
                    state = BtPaymentState.CREATED,
                    state2 = BtPaymentState2.CREATED,
                    paidSat = 0UL,
                    bolt11Invoice = IBtBolt11Invoice(
                        request = "lnmock",
                        state = BtBolt11InvoiceState.PENDING,
                        expiresAt = "2025-07-28T12:00:00Z",
                        updatedAt = "2025-07-28T08:30:00Z"
                    ),
                    onchain = IBtOnchainTransactions(
                        address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                        confirmedSat = 0UL,
                        requiredConfirmations = 1u,
                        transactions = listOf(
                            IBtOnchainTransaction(
                                amountSat = 50000UL,
                                txId = "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16",
                                vout = 0u,
                                blockHeight = null,
                                blockConfirmationCount = 0u,
                                feeRateSatPerVbyte = 12.5,
                                confirmed = false,
                                suspicious0ConfReason = ""
                            )
                        )
                    ),
                    isManuallyPaid = null,
                    manualRefunds = null
                ),
                couponCode = null,
                source = null,
                discount = null,
                updatedAt = "2025-07-28T08:29:03Z",
                createdAt = "2025-07-28T08:29:03Z"
            ),
            isAdvanced = false
        )
    }
}

@Preview(showSystemUi = true, showBackground = true, heightDp = 700, name = "Small screen - Advanced")
@Composable
private fun Preview4() {
    AppThemeSurface {
        Content(
            onBackClick = {},
            onCloseClick = {},
            onLearnMoreClick = {},
            onAdvancedClick = {},
            onConfirm = {},
            onUseDefaultLspBalanceClick = {},
            onTransferToSpendingConfirm = {},
            order = IBtOrder(
                id = "order_7e6f3b7c-486a-4f5a-8b1e-2c9d7f0a8b9d",
                state = BtOrderState.CREATED,
                state2 = BtOrderState2.CREATED,
                feeSat = 1000UL,
                networkFeeSat = 250UL,
                serviceFeeSat = 750UL,
                lspBalanceSat = 2000000UL,
                clientBalanceSat = 500000UL,
                zeroConf = false,
                zeroReserve = true,
                clientNodeId = null,
                channelExpiryWeeks = 8u,
                channelExpiresAt = "2025-09-22T08:29:03Z",
                orderExpiresAt = "2025-07-29T08:29:03Z",
                channel = null,
                lspNode = ILspNode(
                    alias = "Bitkit LSP",
                    pubkey = "02f12451995802149b1855a7948305763328e9304337b51e45e7f1b637956424e8",
                    connectionStrings = listOf("mock@127.0.0.1:9735"),
                    readonly = null
                ),
                lnurl = null,
                payment = IBtPayment(
                    state = BtPaymentState.CREATED,
                    state2 = BtPaymentState2.CREATED,
                    paidSat = 0UL,
                    bolt11Invoice = IBtBolt11Invoice(
                        request = "lnmock",
                        state = BtBolt11InvoiceState.PENDING,
                        expiresAt = "2025-07-28T12:00:00Z",
                        updatedAt = "2025-07-28T08:30:00Z"
                    ),
                    onchain = IBtOnchainTransactions(
                        address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
                        confirmedSat = 0UL,
                        requiredConfirmations = 1u,
                        transactions = listOf(
                            IBtOnchainTransaction(
                                amountSat = 50000UL,
                                txId = "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16",
                                vout = 0u,
                                blockHeight = null,
                                blockConfirmationCount = 0u,
                                feeRateSatPerVbyte = 12.5,
                                confirmed = false,
                                suspicious0ConfReason = ""
                            )
                        )
                    ),
                    isManuallyPaid = null,
                    manualRefunds = null
                ),
                couponCode = null,
                source = null,
                discount = null,
                updatedAt = "2025-07-28T08:29:03Z",
                createdAt = "2025-07-28T08:29:03Z"
            ),
            isAdvanced = true
        )
    }
}
