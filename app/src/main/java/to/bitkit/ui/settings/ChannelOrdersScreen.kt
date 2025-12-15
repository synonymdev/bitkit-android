package to.bitkit.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.synonym.bitkitcore.BtBolt11InvoiceState
import com.synonym.bitkitcore.BtOrderState
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.BtPaymentState
import com.synonym.bitkitcore.BtPaymentState2
import com.synonym.bitkitcore.CJitStateEnum
import com.synonym.bitkitcore.IBtBolt11Invoice
import com.synonym.bitkitcore.IBtOnchainTransaction
import com.synonym.bitkitcore.IBtOnchainTransactions
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IBtPayment
import com.synonym.bitkitcore.IDiscount
import com.synonym.bitkitcore.ILspNode
import com.synonym.bitkitcore.IcJitEntry
import kotlinx.coroutines.launch
import to.bitkit.models.formatToModernDisplay
import to.bitkit.ui.Routes
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.BodySSB
import to.bitkit.ui.components.Caption
import to.bitkit.ui.components.Caption13Up
import to.bitkit.ui.components.CaptionB
import to.bitkit.ui.components.Footnote
import to.bitkit.ui.components.HorizontalSpacer
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.components.VerticalSpacer
import to.bitkit.ui.components.settings.SectionHeader
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.shared.modifiers.clickableAlpha
import to.bitkit.ui.theme.AppShapes
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.ui.utils.copyToClipboard
import to.bitkit.utils.Logger

@Composable
fun ChannelOrdersScreen(
    onBackClick: () -> Unit,
    onOrderItemClick: (String) -> Unit,
    onCjitItemClick: (String) -> Unit,
) {
    val blocktank = blocktankViewModel ?: return
    val orders by blocktank.orders.collectAsStateWithLifecycle()
    val cJitEntries by blocktank.cJitEntries.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { blocktank.refreshOrders() }

    Content(
        orders = orders,
        cJitEntries = cJitEntries,
        onBack = onBackClick,
        onClickOrder = onOrderItemClick,
        onClickCjit = onCjitItemClick,
    )
}

@Composable
private fun Content(
    orders: List<IBtOrder>,
    cJitEntries: List<IcJitEntry>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onClickOrder: (String) -> Unit = {},
    onClickCjit: (String) -> Unit = {},
) {
    Scaffold(
        topBar = { AppTopBar(titleText = "Channel Orders", onBackClick = onBack) },
        modifier = modifier,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .padding(padding)
        ) {
            stickyHeader {
                SectionHeader(title = "Orders", padding = PaddingValues.Zero)
            }
            orders.let { orders ->
                if (orders.isEmpty()) {
                    item {
                        BodyS(text = "No CJIT entries found…")
                    }
                } else {
                    items(orders) { order ->
                        OrderCard(order, onClickOrder)
                    }
                }
            }
            stickyHeader {
                SectionHeader(title = "CJIT Entries", padding = PaddingValues.Zero)
            }
            cJitEntries.let { entries ->
                if (entries.isEmpty()) {
                    item {
                        BodyS(text = "No CJIT entries found…")
                    }
                } else {
                    items(entries) { entry ->
                        CJitCard(entry, onClickCjit)
                    }
                }
            }
        }
    }
}

@Composable
fun OrderDetailScreen(
    orderItem: Routes.OrderDetail,
    onBackClick: () -> Unit = {},
) {
    val blocktank = blocktankViewModel ?: return
    val orders by blocktank.orders.collectAsStateWithLifecycle()
    val order = orders.find { it.id == orderItem.id } ?: return
    val coroutineScope = rememberCoroutineScope()

    OrderDetailContent(
        order = order,
        onBack = onBackClick,
        onClickOpen = {
            coroutineScope.launch {
                Logger.info("Opening channel for order ${order.id}")
                try {
                    blocktank.openChannel(orderId = order.id)
                    Logger.info("Channel opened for order ${order.id}")
                } catch (e: Throwable) {
                    Logger.error("Error opening channel for order ${order.id}", e)
                }
            }
        },
    )
}

@Composable
private fun OrderDetailContent(
    order: IBtOrder,
    onBack: () -> Unit = {},
    onClickOpen: () -> Unit = {},
) {
    Scaffold(
        topBar = { AppTopBar(titleText = "Order Details", onBackClick = onBack) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.padding(padding)
        ) {
            item {
                InfoCard(header = "Order Details") {
                    DetailRow("ID", order.id)
                    DetailRow("Onchain txs", order.payment?.onchain?.transactions?.size?.toString() ?: "0")
                    DetailRow("State", order.state.toString())
                    DetailRow("State 2", order.state2.toString())
                    DetailRow("LSP Balance", order.lspBalanceSat.formatToModernDisplay())
                    DetailRow("Client Balance", order.clientBalanceSat.formatToModernDisplay())
                    DetailRow("Total Fee", order.feeSat.formatToModernDisplay())
                    DetailRow("Network Fee", order.networkFeeSat.formatToModernDisplay())
                    DetailRow("Service Fee", order.serviceFeeSat.formatToModernDisplay())
                }
            }
            item {
                InfoCard(header = "Channel Settings") {
                    DetailRow("Zero Conf", if (order.zeroConf) "Yes" else "No")
                    DetailRow("Zero Reserve", if (order.zeroReserve) "Yes" else "No")
                    order.clientNodeId?.let {
                        DetailRow("Client Node ID", it)
                    }
                    DetailRow("Expiry Weeks", order.channelExpiryWeeks.toString())
                    DetailRow("Channel Expires", order.channelExpiresAt)
                    DetailRow("Order Expires", order.orderExpiresAt)
                }
            }
            item {
                InfoCard(header = "LSP Info") {
                    DetailRow("Alias", order.lspNode?.alias.orEmpty())
                    DetailRow("Node ID", order.lspNode?.pubkey.orEmpty())
                    order.lnurl?.let {
                        DetailRow("LNURL", it)
                    }
                }
            }
            order.couponCode?.let { couponCode ->
                item {
                    InfoCard(header = "Discount") {
                        DetailRow("Coupon Code", couponCode)
                        order.discount?.let { discount ->
                            DetailRow("Discount Type", discount.code)
                            DetailRow("Value", discount.absoluteSat.formatToModernDisplay())
                        }
                    }
                }
            }
            item {
                InfoCard(header = "Timestamps") {
                    DetailRow("Created", order.createdAt)
                    DetailRow("Updated", order.updatedAt)
                }
            }
            if (order.state2 == BtOrderState2.PAID) {
                item {
                    PrimaryButton(
                        text = "Open Channel",
                        onClick = onClickOpen,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun CJitDetailScreen(
    cjitItem: Routes.CjitDetail,
    onBackClick: () -> Unit = {},
) {
    val blocktank = blocktankViewModel ?: return
    val cJitEntries by blocktank.cJitEntries.collectAsStateWithLifecycle()
    val entry = cJitEntries.find { it.id == cjitItem.id } ?: return
    CJitDetailContent(
        entry = entry,
        onBack = onBackClick,
    )
}

@Composable
private fun CJitDetailContent(
    entry: IcJitEntry,
    onBack: () -> Unit = {},
) {
    Scaffold(
        topBar = { AppTopBar(titleText = "CJIT Details", onBackClick = onBack) }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(padding)
        ) {
            item {
                InfoCard(header = "CJIT Details") {
                    DetailRow(label = "ID", value = entry.id)
                    DetailRow(label = "State", value = entry.state.toString())
                    DetailRow(label = "Channel Size", value = entry.channelSizeSat.formatToModernDisplay())
                    entry.channelOpenError?.let { error ->
                        DetailRow(label = "Error", value = error, isError = true)
                    }
                }
            }
            item {
                InfoCard(header = "Fees") {
                    DetailRow(label = "Total Fee", value = entry.feeSat.formatToModernDisplay())
                    DetailRow(label = "Network Fee", value = entry.networkFeeSat.formatToModernDisplay())
                    DetailRow(label = "Service Fee", value = entry.serviceFeeSat.formatToModernDisplay())
                }
            }
            item {
                InfoCard(header = "Channel Settings") {
                    DetailRow(label = "Node ID", value = entry.nodeId)
                    DetailRow(label = "Expiry Weeks", value = "${entry.channelExpiryWeeks}")
                }
            }
            item {
                InfoCard(header = "LSP Information") {
                    DetailRow(label = "Alias", value = entry.lspNode.alias)
                    DetailRow(label = "Node ID", value = entry.lspNode.pubkey)
                }
            }
            if (entry.couponCode.isNotEmpty()) {
                item {
                    InfoCard(header = "Discount") {
                        DetailRow(label = "Coupon Code", value = entry.couponCode)
                        entry.discount?.let { discount ->
                            DetailRow(label = "Discount Type", value = discount.code)
                            DetailRow(label = "Value", value = "${discount.absoluteSat}")
                        }
                    }
                }
            }
            item {
                InfoCard(header = "Timestamps") {
                    DetailRow(label = "Created", value = entry.createdAt)
                    DetailRow(label = "Updated", value = entry.updatedAt)
                    DetailRow(label = "Expires", value = entry.expiresAt)
                }
            }
        }
    }
}

private val cardColors: CardColors @Composable get() = CardDefaults.cardColors(containerColor = Colors.White10)

@Composable
private fun InfoCard(
    header: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        SectionHeader(header, padding = PaddingValues.Zero)
        Card(
            colors = cardColors,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun OrderCard(model: IBtOrder, onClick: (String) -> Unit) {
    Card(
        colors = cardColors,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(model.id) }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CaptionB(
                    text = model.id,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.clickableAlpha(onClick = copyToClipboard(model.id))
                )
                Surface(color = Colors.White16, shape = AppShapes.small) {
                    Footnote(
                        text = model.state2.toString(),
                        color = Colors.White64,
                        maxLines = 1,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                InfoCell(label = "LSP Balance", value = model.lspBalanceSat.formatToModernDisplay())
                InfoCell(
                    label = "Client Balance",
                    value = model.clientBalanceSat.formatToModernDisplay(),
                    alignment = Alignment.End
                )
            }

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                InfoCell(label = "Fees", value = model.feeSat.formatToModernDisplay())
                InfoCell(
                    label = "Expires",
                    value = model.channelExpiresAt.take(10),
                    alignment = Alignment.End
                )
            }
        }
    }
}

@Composable
private fun CJitCard(model: IcJitEntry, onClick: (String) -> Unit) {
    Card(
        colors = cardColors,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(model.id) }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CaptionB(
                    text = model.id,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.clickableAlpha(onClick = copyToClipboard(model.id))
                )
                Surface(color = Colors.White16, shape = MaterialTheme.shapes.small) {
                    Footnote(
                        text = model.state.toString(),
                        color = Colors.White64,
                        maxLines = 1,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoCell(label = "Channel Size", value = "${model.channelSizeSat.formatToModernDisplay()} sats")
                InfoCell(
                    label = "Fees",
                    value = "${model.feeSat.formatToModernDisplay()} sats",
                    alignment = Alignment.End
                )
            }

            Column {
                model.channelOpenError?.let { error ->
                    Caption(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                VerticalSpacer(4.dp)
                Footnote(text = "Expires: ${model.expiresAt.take(10)}", color = Colors.White32)
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Caption13Up(text = label, color = Colors.White64)
        VerticalSpacer(4.dp)
        BodySSB(text = value)
    }
}

@Composable
private fun DetailRow(label: String, value: String, isError: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Caption(
            text = label,
            color = Colors.White64,
            overflow = TextOverflow.MiddleEllipsis,
            maxLines = 1,
        )
        HorizontalSpacer(16.dp)
        Caption(
            text = value,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            overflow = TextOverflow.MiddleEllipsis,
            maxLines = 1,
            modifier = Modifier.clickableAlpha(onClick = copyToClipboard(value))
        )
    }
}

@Suppress("SpellCheckingInspection")
private val order = IBtOrder(
    id = "order-3c564573-ec4b-b502-5e6fe930435f",
    state = BtOrderState.CREATED,
    state2 = BtOrderState2.PAID,
    feeSat = 67_890uL,
    networkFeeSat = 234uL,
    serviceFeeSat = 345uL,
    lspBalanceSat = 123_456uL,
    clientBalanceSat = 56_789uL,
    zeroConf = false,
    zeroReserve = true,
    clientNodeId = "027276bb015830eac83faa8267feb9a510ad6ca9d3408c39e67a0b16c968a0e504",
    channelExpiryWeeks = 6u,
    channelExpiresAt = "2025-03-19T14:42:03.175Z",
    orderExpiresAt = "2025-02-05T14:42:03.175Z",
    channel = null,
    lspNode = ILspNode(
        alias = "LSP-Node-Alias",
        pubkey = "lspNodePubkey",
        connectionStrings = emptyList(),
        readonly = true,
    ),
    lnurl = "LNURL1DP68GURN8GHJ7CTSDYH8XARPVUHXYMR0VD4HGCTWDVH8GME0VFKX7CMTW3SKU6E0V9CXJTMKXGHKCTENVV6NVDP4XUEJ6ETRX33Z6DPEXU6Z6C34XQEZ6DT9XENX2WFNXQ6RXDTXGQAH4MLNURL1DP68GURN8GHJ7CTSDYH8XARPVUHXYMR0VD4HGCTWDVH8GME0VFKX7CMTW3SKU6E0V9CXJTMKXGHKCTENVV6NVDP4XUEJ6ETRX33Z6DPEXU6Z6C34XQEZ6DT9XENX2WFNXQ6RXDTXGQAH4M",
    payment = IBtPayment(
        state = BtPaymentState.PAID,
        state2 = BtPaymentState2.PAID,
        paidSat = 234_567uL,
        bolt11Invoice = IBtBolt11Invoice(
            request = "bolt11RequestOrder",
            state = BtBolt11InvoiceState.PAID,
            expiresAt = "2025-02-03T14:42:03.175Z",
            updatedAt = "2025-02-03T14:42:03.175Z",
        ),
        onchain = IBtOnchainTransactions(
            address = "onchainAddress",
            confirmedSat = 345_678uL,
            requiredConfirmations = 6u,
            transactions = listOf(
                IBtOnchainTransaction(
                    amountSat = 345_678uL,
                    txId = "onchainTxId",
                    vout = 4u,
                    blockHeight = 789u,
                    blockConfirmationCount = 34u,
                    feeRateSatPerVbyte = 1.2,
                    confirmed = true,
                    suspicious0ConfReason = "suspicious0ConfReason"
                ),
            ),
        ),
        isManuallyPaid = true,
        manualRefunds = null,
    ),
    couponCode = "coupon-code-order",
    source = "orderSource",
    discount = IDiscount(
        code = "discount-code-order",
        absoluteSat = 123uL,
        relative = 1.2,
        overallSat = 234uL,
    ),
    updatedAt = "2025-02-03T14:42:03.175Z",
    createdAt = "2025-02-03T14:43:03.564Z",
)

private val cjitEntry = IcJitEntry(
    id = "cjit-3c564573-4974-b502-5e6fe930435f",
    state = CJitStateEnum.CREATED,
    feeSat = 45_670uL,
    networkFeeSat = 56_789uL,
    serviceFeeSat = 67_890uL,
    channelSizeSat = 50_123uL,
    channelExpiryWeeks = 6u,
    channelOpenError = "channelOpenError",
    nodeId = "cjit-nodeId",
    invoice = IBtBolt11Invoice(
        request = "bolt11RequestCjit",
        state = BtBolt11InvoiceState.PAID,
        expiresAt = "2025-02-03T14:42:03.175Z",
        updatedAt = "2025-02-03T14:42:03.175Z",
    ),
    channel = null,
    lspNode = ILspNode(
        alias = "LSP-Node-Alias",
        pubkey = "028a8910b0048630d4eb17af25668cdd7ea6f2d8ae20956e7a06e2ae46ebcb69fc",
        connectionStrings = emptyList(),
        readonly = true,
    ),
    couponCode = "coupon-code-cjit",
    source = "cjitSource",
    discount = IDiscount(
        code = "discount-code-order",
        absoluteSat = 123uL,
        relative = 1.2,
        overallSat = 234uL,
    ),
    expiresAt = "2025-02-05T14:42:03.175Z",
    updatedAt = "2025-02-03T14:42:03.175Z",
    createdAt = "2025-02-03T14:43:03.564Z",
)

@Preview(showSystemUi = true)
@Composable
private fun Preview() {
    AppThemeSurface {
        Content(
            orders = listOf(order),
            cJitEntries = listOf(cjitEntry),
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewEmpty() {
    AppThemeSurface {
        Content(
            orders = emptyList(),
            cJitEntries = emptyList(),
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewOrderDetail() {
    AppThemeSurface {
        OrderDetailContent(
            order = order,
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun PreviewCJitDetail() {
    AppThemeSurface {
        CJitDetailContent(
            entry = cjitEntry,
        )
    }
}
