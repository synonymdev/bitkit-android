package to.bitkit.ui.settings.lightning

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.synonym.bitkitcore.BtBolt11InvoiceState
import com.synonym.bitkitcore.BtOpenChannelState
import com.synonym.bitkitcore.BtOrderState
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.BtPaymentState
import com.synonym.bitkitcore.BtPaymentState2
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.FundingTx
import com.synonym.bitkitcore.IBtBolt11Invoice
import com.synonym.bitkitcore.IBtChannel
import com.synonym.bitkitcore.IBtOnchainTransactions
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IBtPayment
import com.synonym.bitkitcore.IDiscount
import com.synonym.bitkitcore.ILspNode
import com.synonym.bitkitcore.IcJitEntry
import org.lightningdevkit.ldknode.OutPoint
import to.bitkit.R
import to.bitkit.env.Env
import to.bitkit.ext.DatePattern
import to.bitkit.ext.amountOnClose
import to.bitkit.ext.createChannelDetails
import to.bitkit.ext.setClipboardText
import to.bitkit.models.Toast
import to.bitkit.ui.appViewModel
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.ChannelStatusUi
import to.bitkit.ui.components.FillHeight
import to.bitkit.ui.components.LightningChannel
import to.bitkit.ui.components.MoneyCaptionB
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.SecondaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.navigateToHome
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.scaffold.CloseNavIcon
import to.bitkit.ui.scaffold.ScreenColumn
import to.bitkit.ui.settings.lightning.components.ChannelStatusView
import to.bitkit.ui.shared.util.clickableAlpha
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.getBlockExplorerUrl
import to.bitkit.ui.walletViewModel
import to.bitkit.utils.TxDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ChannelDetailScreen(
    navController: NavController,
    viewModel: LightningConnectionsViewModel,
) {
    val context = LocalContext.current
    val app = appViewModel ?: return
    val wallet = walletViewModel ?: return
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearSelectedChannel()
            viewModel.clearTransactionDetails()
        }
    }

    val selectedChannel by viewModel.selectedChannel.collectAsStateWithLifecycle()
    val channel = selectedChannel ?: return

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val paidOrders by viewModel.blocktankRepo.blocktankState.collectAsStateWithLifecycle()
    val txDetails by viewModel.txDetails.collectAsStateWithLifecycle()
    val walletState by wallet.uiState.collectAsStateWithLifecycle()

    // Fetch transaction details for funding transaction if available
    LaunchedEffect(channel.details.fundingTxo?.txid) {
        channel.details.fundingTxo?.txid?.let { txid ->
            viewModel.fetchTransactionDetails(txid)
        }
    }

    Content(
        channel = channel,
        blocktankOrders = paidOrders.paidOrders,
        cjitEntries = paidOrders.cjitEntries,
        txDetails = txDetails,
        isRefreshing = uiState.isRefreshing,
        onBack = { navController.popBackStack() },
        onClose = { navController.navigateToHome() },
        onRefresh = {
            viewModel.onPullToRefresh()
        },
        onCopyText = { text ->
            context.setClipboardText(text)
            app.toast(
                type = Toast.ToastType.SUCCESS,
                title = context.getString(R.string.common__copied),
                description = text,
            )
        },
        onOpenUrl = { url ->
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            context.startActivity(intent)
        },
        onSupport = { order ->
            val intent = createSupportEmailIntent(
                order = order,
                channel = channel,
                nodeId = walletState.nodeId,
            )
            // Try to open email intent, fallback to browser if no email app
            try {
                context.startActivity(Intent.createChooser(intent, context.getString(R.string.lightning__support)))
            } catch (_: Throwable) {
                // Fallback to opening support website
                val fallbackIntent = Intent(Intent.ACTION_VIEW, Env.SYNONYM_CONTACT.toUri())
                context.startActivity(fallbackIntent)
            }
        },
        onCloseConnection = {
            // TODO: Implement close connection screen
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Content(
    channel: ChannelUi,
    blocktankOrders: List<IBtOrder> = emptyList(),
    cjitEntries: List<IcJitEntry> = emptyList(),
    txDetails: TxDetails? = null,
    isRefreshing: Boolean = false,
    onBack: () -> Unit = {},
    onClose: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onCopyText: (String) -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
    onSupport: (Any) -> Unit = {},
    onCloseConnection: () -> Unit = {},
) {
    // Check if the channel was opened via CJIT
    val cjitEntry = cjitEntries.find { entry ->
        entry.channel?.fundingTx?.id == channel.details.fundingTxo?.txid
    }

    // Check if the channel was opened via blocktank order
    val blocktankOrder = blocktankOrders.find { order ->
        // real channel
        if (channel.details.fundingTxo?.txid != null) {
            order.channel?.fundingTx?.id == channel.details.fundingTxo?.txid
        } else {
            // fake channel
            order.id == channel.details.channelId
        }
    }

    val order = blocktankOrder ?: cjitEntry

    val capacity = channel.details.channelValueSats.toLong()
    val localBalance = channel.details.amountOnClose.toLong()
    val remoteBalance = (channel.details.inboundCapacityMsat / 1000u).toLong()
    val reserveBalance = (channel.details.unspendablePunishmentReserve ?: 0u).toLong()

    ScreenColumn {
        AppTopBar(
            titleText = channel.name,
            onBackClick = onBack,
            actions = { CloseNavIcon(onClose) },
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
            ) {
                // Channel Display Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 32.dp)
                ) {
                    VerticalSpacer(16.dp)
                    LightningChannel(
                        capacity = capacity,
                        localBalance = localBalance,
                        remoteBalance = remoteBalance,
                        status = getChannelStatus(channel, blocktankOrder),
                    )
                }
                HorizontalDivider()

                // Status Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                ) {
                    SectionTitle(stringResource(R.string.lightning__status))
                    ChannelStatusView(
                        channel = channel,
                        blocktankOrder = blocktankOrder,
                    )
                }
                HorizontalDivider()

                // Order Details Section
                if (order != null) {
                    Column {
                        SectionTitle(stringResource(R.string.lightning__order_details))

                        val orderId = when (order) {
                            is IBtOrder -> order.id
                            is IcJitEntry -> order.id
                            else -> ""
                        }
                        val createdAt = when (order) {
                            is IBtOrder -> order.createdAt
                            is IcJitEntry -> order.createdAt
                            else -> ""
                        }

                        SectionRow(
                            name = stringResource(R.string.lightning__order),
                            valueContent = {
                                CaptionB(
                                    text = orderId,
                                    maxLines = 1,
                                    overflow = TextOverflow.MiddleEllipsis,
                                    textAlign = TextAlign.End,
                                )
                            },
                            onClick = { onCopyText(orderId) }
                        )

                        SectionRow(
                            name = stringResource(R.string.lightning__created_on),
                            valueContent = {
                                CaptionB(text = formatDate(createdAt))
                            }
                        )

                        // Order expiry for pending blocktank orders only
                        if (blocktankOrder != null &&
                            (blocktankOrder.state2 == BtOrderState2.CREATED || blocktankOrder.state2 == BtOrderState2.PAID)
                        ) {
                            SectionRow(
                                name = stringResource(R.string.lightning__order_expiry),
                                valueContent = {
                                    CaptionB(text = formatDate(blocktankOrder.orderExpiresAt))
                                }
                            )
                        }

                        // Transaction details if available
                        val fundingTxId = when (order) {
                            is IBtOrder -> order.channel?.fundingTx?.id
                            is IcJitEntry -> order.channel?.fundingTx?.id
                            else -> null
                        }
                        fundingTxId?.let { txId ->
                            SectionRow(
                                name = stringResource(R.string.lightning__transaction),
                                valueContent = {
                                    CaptionB(
                                        text = txId,
                                        maxLines = 1,
                                        overflow = TextOverflow.MiddleEllipsis,
                                        textAlign = TextAlign.End,
                                    )
                                },
                                onClick = {
                                    onCopyText(txId)
                                    onOpenUrl(getBlockExplorerUrl(txId))
                                }
                            )
                        }

                        // Order fee
                        val orderFee = when (order) {
                            is IBtOrder -> order.feeSat - order.clientBalanceSat
                            is IcJitEntry -> order.feeSat
                            else -> 0u
                        }
                        if (orderFee > 0u) {
                            SectionRow(
                                name = stringResource(R.string.lightning__order_fee),
                                valueContent = {
                                    MoneyCaptionB(sats = orderFee.toLong(), symbol = true)
                                }
                            )
                        }
                    }
                }

                // Balance Section
                SectionTitle(stringResource(R.string.lightning__balance))

                SectionRow(
                    name = stringResource(R.string.lightning__receiving_label),
                    valueContent = {
                        MoneyCaptionB(sats = remoteBalance, symbol = true)
                    }
                )

                SectionRow(
                    name = stringResource(R.string.lightning__spending_label),
                    valueContent = {
                        MoneyCaptionB(sats = localBalance, symbol = true)
                    }
                )

                SectionRow(
                    name = stringResource(R.string.lightning__reserve_balance),
                    valueContent = {
                        MoneyCaptionB(sats = reserveBalance, symbol = true)
                    }
                )

                SectionRow(
                    name = stringResource(R.string.lightning__total_size),
                    valueContent = {
                        MoneyCaptionB(sats = capacity, symbol = true)
                    }
                )

                // Fees Section
                SectionTitle(stringResource(R.string.lightning__fees))

                SectionRow(
                    name = stringResource(R.string.lightning__base_fee),
                    valueContent = {
                        MoneyCaptionB(
                            sats = (channel.details.config.forwardingFeeBaseMsat / 1000u).toLong(),
                            symbol = true
                        )
                    }
                )

                SectionRow(
                    name = stringResource(R.string.lightning__fee_rate),
                    valueContent = {
                        CaptionB(text = "${channel.details.config.forwardingFeeProportionalMillionths} ppm")
                    }
                )

                // Other Section
                SectionTitle(stringResource(R.string.lightning__other))

                SectionRow(
                    name = stringResource(R.string.lightning__is_usable),
                    valueContent = {
                        CaptionB(
                            text = stringResource(
                                if (channel.details.isUsable) R.string.common__yes else R.string.common__no
                            )
                        )
                    }
                )

                val fundingTxId = channel.details.fundingTxo?.txid
                val txTime = if (fundingTxId != null && txDetails?.txid == fundingTxId) {
                    txDetails.status.block_time
                } else null

                txTime?.let {
                    SectionRow(
                        name = stringResource(R.string.lightning__opened_on),
                        valueContent = {
                            CaptionB(text = formatUnixTimestamp(txTime))
                        }
                    )
                }

                // Closed date for closed channels
                val orderClosedAt = when (order) {
                    is IBtOrder -> order.channel?.close?.registeredAt
                    is IcJitEntry -> order.channel?.close?.registeredAt
                    else -> null
                }
                orderClosedAt?.let { closedAt ->
                    SectionRow(
                        name = stringResource(R.string.lightning__closed_on),
                        valueContent = {
                            CaptionB(text = formatDate(closedAt))
                        }
                    )
                }

                // Channel ID
                SectionRow(
                    name = stringResource(R.string.lightning__channel_id),
                    valueContent = {
                        CaptionB(
                            text = channel.details.channelId,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            textAlign = TextAlign.End,
                        )
                    },
                    onClick = { onCopyText(channel.details.channelId) }
                )

                // Channel point (funding transaction + output index)
                channel.details.fundingTxo?.let { fundingTxo ->
                    val channelPoint = "${fundingTxo.txid}:${fundingTxo.vout}"
                    SectionRow(
                        name = stringResource(R.string.lightning__channel_point),
                        valueContent = {
                            CaptionB(
                                text = channelPoint,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                textAlign = TextAlign.End,
                            )
                        },
                        onClick = { onCopyText(channelPoint) }
                    )
                }

                // Peer ID
                SectionRow(
                    name = stringResource(R.string.lightning__channel_node_id),
                    valueContent = {
                        CaptionB(
                            text = channel.details.counterpartyNodeId,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            textAlign = TextAlign.End,
                        )
                    },
                    onClick = { onCopyText(channel.details.counterpartyNodeId) }
                )

                // TODO add closure reason when tracking closed channels
                // val channelClosureReason: String? = null
                // if (channelClosureReason != null) {
                //     SectionRow(
                //         name = stringResource(R.string.lightning__closure_reason),
                //         valueContent = {
                //             CaptionB(text = channelClosureReason)
                //         },
                //     )
                // }

                // Action Buttons
                FillHeight()
                VerticalSpacer(32.dp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (order != null) {
                        SecondaryButton(
                            text = stringResource(R.string.lightning__support),
                            onClick = { onSupport(order) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (channel.details.isChannelReady) {
                        PrimaryButton(
                            text = stringResource(R.string.lightning__close_conn),
                            onClick = onCloseConnection,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                VerticalSpacer(16.dp)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    VerticalSpacer(32.dp)
    Caption13Up(text = text, color = Colors.White64)
    VerticalSpacer(8.dp)
}

@Composable
private fun SectionRow(
    name: String,
    valueContent: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clickableAlpha(onClick = onClick)
    ) {
        CaptionB(
            text = name,
            modifier = Modifier.weight(1f)
        )
        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier.weight(1.5f),
        ) {
            valueContent()
        }
    }
    HorizontalDivider()
}

private fun getChannelStatus(
    channel: ChannelUi,
    blocktankOrder: IBtOrder?,
): ChannelStatusUi {
    blocktankOrder?.let { order ->
        when {
            order.state2 == BtOrderState2.EXPIRED ||
                order.payment.state2 == BtPaymentState2.CANCELED ||
                order.payment.state2 == BtPaymentState2.REFUNDED -> {
                return ChannelStatusUi.CLOSED
            }

            order.state2 == BtOrderState2.CREATED ||
                order.state2 == BtOrderState2.PAID ||
                order.channel?.state == BtOpenChannelState.OPENING -> {
                return ChannelStatusUi.PENDING
            }
        }
    }

    return if (channel.details.isChannelReady) ChannelStatusUi.OPEN else ChannelStatusUi.PENDING
}

private fun formatDate(dateString: String): String {
    return runCatching {
        val instant = Instant.parse(dateString)
        val formatter = DateTimeFormatter.ofPattern(DatePattern.CHANNEL_DETAILS, Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    }.getOrDefault(dateString)
}

private fun formatUnixTimestamp(timestamp: Long): String {
    return runCatching {
        val instant = Instant.ofEpochSecond(timestamp)
        val formatter = DateTimeFormatter.ofPattern(DatePattern.CHANNEL_DETAILS, Locale.getDefault())
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    }.getOrDefault(timestamp.toString())
}

private fun createSupportEmailIntent(
    order: Any, // IBtOrder or IcJitEntry
    channel: ChannelUi,
    nodeId: String,
): Intent {
    val subject = "Bitkit Support [Channel]"

    val orderId = when (order) {
        is IBtOrder -> order.id
        is IcJitEntry -> order.id
        else -> "Unknown"
    }

    val fundingTxId = when (order) {
        is IBtOrder -> order.channel?.fundingTx?.id
        is IcJitEntry -> order.channel?.fundingTx?.id
        else -> channel.details.fundingTxo?.txid
    }

    val body = buildString {
        if (orderId.isNotEmpty()) {
            appendLine("Blocktank order ID: $orderId")
        }

        appendLine("Transaction ID: $fundingTxId")
        appendLine("Platform: ${Env.platform}")
        appendLine("Version: ${Env.version}")
        appendLine("LDK node ID: $nodeId")
    }.trim()

    return Intent(Intent.ACTION_SENDTO).apply {
        data = "mailto:${Env.SUPPORT_EMAIL}".toUri()
        putExtra(Intent.EXTRA_EMAIL, arrayOf(Env.SUPPORT_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, body)
    }
}

@Preview
@Composable
private fun PreviewOpenChannel() {
    AppThemeSurface {
        Content(
            channel = ChannelUi(
                name = "Connection 1",
                details = createChannelDetails().copy(
                    channelId = "channel_1",
                    channelValueSats = 100_000_000u,
                    outboundCapacityMsat = 25_000_000u,
                    inboundCapacityMsat = 75_000_000u,
                    fundingTxo = OutPoint(txid = "sample_txid", vout = 0u),
                    isChannelReady = true,
                    isUsable = true,
                ),
            ),
            txDetails = null,
        )
    }
}

@Preview
@Composable
private fun PreviewChannelWithOrder() {
    AppThemeSurface {
        Content(
            channel = ChannelUi(
                name = "Connection 2",
                details = createChannelDetails().copy(
                    channelId = "bt_order_12345",
                    channelValueSats = 500_000u,
                    outboundCapacityMsat = 100_000_000u,
                    inboundCapacityMsat = 400_000_000u,
                    fundingTxo = OutPoint(
                        txid = "abcd1234567890abcd1234567890abcd1234567890abcd1234567890abcd1234",
                        vout = 1u
                    ),
                    isChannelReady = true,
                    isUsable = true,
                ),
            ),
            blocktankOrders = listOf(
                IBtOrder(
                    id = "bt_order_12345",
                    state = BtOrderState.OPEN,
                    state2 = BtOrderState2.EXECUTED,
                    feeSat = 115000u,
                    networkFeeSat = 5000u,
                    serviceFeeSat = 10000u,
                    lspBalanceSat = 400000u,
                    clientBalanceSat = 100000u,
                    zeroConf = true,
                    zeroReserve = false,
                    clientNodeId = "client_node_123",
                    channelExpiryWeeks = 52u,
                    channelExpiresAt = "2025-01-15T10:30:00.000Z",
                    orderExpiresAt = "2024-02-15T10:30:00.000Z",
                    channel = IBtChannel(
                        state = BtOpenChannelState.OPEN,
                        lspNodePubkey = "lsp_node_pubkey_abc123",
                        clientNodePubkey = "client_node_pubkey_def456",
                        announceChannel = true,
                        fundingTx = FundingTx(
                            id = "abcd1234567890abcd1234567890abcd1234567890abcd1234567890abcd1234",
                            vout = 1u
                        ),
                        closingTxId = null,
                        close = null,
                        shortChannelId = "123456x789x1"
                    ),
                    lspNode = ILspNode(
                        alias = "Synonym LSP",
                        pubkey = "lsp_node_pubkey_abc123",
                        connectionStrings = listOf("lsp.synonym.to:9735"),
                        readonly = false
                    ),
                    lnurl = null,
                    payment = IBtPayment(
                        state = BtPaymentState.PAID,
                        state2 = BtPaymentState2.PAID,
                        paidSat = 115000u,
                        bolt11Invoice = IBtBolt11Invoice(
                            request = "lnbc1150u1p...",
                            state = BtBolt11InvoiceState.PAID,
                            expiresAt = "2024-01-15T11:30:00.000Z",
                            updatedAt = "2024-01-15T10:35:00.000Z"
                        ),
                        onchain = IBtOnchainTransactions(
                            address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                            confirmedSat = 115000u,
                            requiredConfirmations = 1u,
                            transactions = emptyList()
                        ),
                        isManuallyPaid = false,
                        manualRefunds = null
                    ),
                    couponCode = null,
                    source = "bitkit-android",
                    discount = null,
                    updatedAt = "2024-01-15T10:35:00.000Z",
                    createdAt = "2024-01-15T10:30:00.000Z"
                )
            ),
            txDetails = null,
        )
    }
}

@Preview
@Composable
private fun PreviewPendingOrder() {
    AppThemeSurface {
        Content(
            channel = ChannelUi(
                name = "Connection 3 (Pending)",
                details = createChannelDetails().copy(
                    channelId = "pending_order_67890",
                    channelValueSats = 300_000u,
                    outboundCapacityMsat = 50_000_000u,
                    inboundCapacityMsat = 250_000_000u,
                    isChannelReady = false,
                    isUsable = false,
                ),
            ),
            blocktankOrders = listOf(
                IBtOrder(
                    id = "pending_order_67890",
                    state = BtOrderState.CREATED,
                    state2 = BtOrderState2.PAID,
                    feeSat = 58000u,
                    networkFeeSat = 3000u,
                    serviceFeeSat = 5000u,
                    lspBalanceSat = 250000u,
                    clientBalanceSat = 50000u,
                    zeroConf = false,
                    zeroReserve = false,
                    clientNodeId = null,
                    channelExpiryWeeks = 26u,
                    channelExpiresAt = "2024-07-15T14:20:00.000Z",
                    orderExpiresAt = "2024-01-16T14:20:00.000Z",
                    channel = IBtChannel(
                        state = BtOpenChannelState.OPENING,
                        lspNodePubkey = "lsp_node_pubkey_xyz789",
                        clientNodePubkey = "client_node_pubkey_uvw012",
                        announceChannel = false,
                        fundingTx = FundingTx(
                            id = "efgh5678901234efgh5678901234efgh5678901234efgh5678901234efgh5678",
                            vout = 0u
                        ),
                        closingTxId = null,
                        close = null,
                        shortChannelId = null
                    ),
                    lspNode = ILspNode(
                        alias = "Test LSP Node",
                        pubkey = "lsp_node_pubkey_xyz789",
                        connectionStrings = listOf("test.lsp.com:9735"),
                        readonly = null
                    ),
                    lnurl = "lnurl1dp68gurn8ghj7ampd3kx2ar0veekzar0wd5xjtnrdakj7tnhv4kxctttdehhwm30d3h82unvwqhk",
                    payment = IBtPayment(
                        state = BtPaymentState.PAID,
                        state2 = BtPaymentState2.PAID,
                        paidSat = 58000u,
                        bolt11Invoice = IBtBolt11Invoice(
                            request = "lnbc580u1p...",
                            state = BtBolt11InvoiceState.PAID,
                            expiresAt = "2024-01-15T15:20:00.000Z",
                            updatedAt = "2024-01-15T14:25:00.000Z"
                        ),
                        onchain = IBtOnchainTransactions(
                            address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                            confirmedSat = 58000u,
                            requiredConfirmations = 1u,
                            transactions = emptyList()
                        ),
                        isManuallyPaid = false,
                        manualRefunds = null
                    ),
                    couponCode = "SAVE10",
                    source = "mobile_app",
                    discount = IDiscount(
                        code = "SAVE10",
                        absoluteSat = 1000u,
                        relative = 0.1,
                        overallSat = 1000u
                    ),
                    updatedAt = "2024-01-15T14:25:00.000Z",
                    createdAt = "2024-01-15T14:20:00.000Z"
                )
            ),
            txDetails = null,
        )
    }
}

@Preview
@Composable
private fun PreviewExpiredOrder() {
    AppThemeSurface {
        Content(
            channel = ChannelUi(
                name = "Connection 4 (Failed)",
                details = createChannelDetails().copy(
                    channelId = "expired_order_99999",
                    channelValueSats = 200_000u,
                    outboundCapacityMsat = 40_000u,
                    inboundCapacityMsat = 160_000u,
                    isChannelReady = false,
                    isUsable = false,
                ),
            ),
            blocktankOrders = listOf(
                IBtOrder(
                    id = "expired_order_99999",
                    state = BtOrderState.EXPIRED,
                    state2 = BtOrderState2.EXPIRED,
                    feeSat = 46000u,
                    networkFeeSat = 2000u,
                    serviceFeeSat = 4000u,
                    lspBalanceSat = 160000u,
                    clientBalanceSat = 40000u,
                    zeroConf = false,
                    zeroReserve = true,
                    clientNodeId = null,
                    channelExpiryWeeks = 12u,
                    channelExpiresAt = "2024-04-15T12:00:00.000Z",
                    orderExpiresAt = "2024-01-14T12:00:00.000Z",
                    channel = null,
                    lspNode = ILspNode(
                        alias = "Lightning Labs",
                        pubkey = "lsp_expired_node_abc",
                        connectionStrings = listOf("expired.lightning.com:9735"),
                        readonly = true
                    ),
                    lnurl = null,
                    payment = IBtPayment(
                        state = BtPaymentState.REFUND_AVAILABLE,
                        state2 = BtPaymentState2.REFUND_AVAILABLE,
                        paidSat = 46000u,
                        bolt11Invoice = IBtBolt11Invoice(
                            request = "lnbc460u1p...",
                            state = BtBolt11InvoiceState.CANCELED,
                            expiresAt = "2024-01-14T13:00:00.000Z",
                            updatedAt = "2024-01-14T12:30:00.000Z"
                        ),
                        onchain = IBtOnchainTransactions(
                            address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                            confirmedSat = 46000u,
                            requiredConfirmations = 1u,
                            transactions = emptyList()
                        ),
                        isManuallyPaid = false,
                        manualRefunds = null
                    ),
                    couponCode = null,
                    source = "web_interface",
                    discount = null,
                    updatedAt = "2024-01-14T12:30:00.000Z",
                    createdAt = "2024-01-14T11:45:00.000Z"
                )
            ),
            txDetails = null,
        )
    }
}

@Preview
@Composable
private fun PreviewChannelWithCjit() {
    AppThemeSurface {
        Content(
            channel = ChannelUi(
                name = "CJIT Connection",
                details = createChannelDetails().copy(
                    channelId = "cjit_channel_456",
                    channelValueSats = 750_000u,
                    outboundCapacityMsat = 150_000_000u,
                    inboundCapacityMsat = 600_000_000u,
                    fundingTxo = OutPoint(
                        txid = "cjit7890123456cjit7890123456cjit7890123456cjit7890123456cjit789012",
                        vout = 0u
                    ),
                    isChannelReady = true,
                    isUsable = true,
                ),
            ),
            cjitEntries = listOf(
                IcJitEntry(
                    id = "cjit_entry_456",
                    state = CJitStateEnum.COMPLETED,
                    feeSat = 162000u,
                    networkFeeSat = 4000u,
                    serviceFeeSat = 8000u,
                    channelSizeSat = 750000u,
                    channelExpiryWeeks = 52u,
                    channelOpenError = null,
                    nodeId = "cjit_node_pubkey_123",
                    invoice = IBtBolt11Invoice(
                        request = "lnbc1620u1p...",
                        state = BtBolt11InvoiceState.PAID,
                        expiresAt = "2024-01-16T12:00:00.000Z",
                        updatedAt = "2024-01-16T11:35:00.000Z"
                    ),
                    channel = IBtChannel(
                        state = BtOpenChannelState.OPEN,
                        lspNodePubkey = "cjit_lsp_pubkey_789",
                        clientNodePubkey = "cjit_client_pubkey_012",
                        announceChannel = true,
                        fundingTx = FundingTx(
                            id = "cjit7890123456cjit7890123456cjit7890123456cjit7890123456cjit789012",
                            vout = 0u
                        ),
                        closingTxId = null,
                        close = null,
                        shortChannelId = "987654x321x0"
                    ),
                    lspNode = ILspNode(
                        alias = "CJIT LSP Provider",
                        pubkey = "cjit_lsp_pubkey_789",
                        connectionStrings = listOf("cjit.provider.com:9735"),
                        readonly = false
                    ),
                    couponCode = "CJIT15",
                    source = "cjit_app",
                    discount = IDiscount(
                        code = "CJIT15",
                        absoluteSat = 2000u,
                        relative = 0.15,
                        overallSat = 2000u
                    ),
                    expiresAt = "2024-02-16T11:30:00.000Z",
                    updatedAt = "2024-01-16T11:35:00.000Z",
                    createdAt = "2024-01-16T11:30:00.000Z"
                )
            ),
            txDetails = null,
        )
    }
}
