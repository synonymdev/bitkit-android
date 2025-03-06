package to.bitkit.ui.settings

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import to.bitkit.ext.formatWithDotSeparator
import to.bitkit.ui.Routes
import to.bitkit.ui.blocktankViewModel
import to.bitkit.ui.components.BodyS
import to.bitkit.ui.components.PrimaryButton
import to.bitkit.ui.scaffold.AppTopBar
import to.bitkit.ui.theme.AppThemeSurface
import to.bitkit.ui.theme.Colors
import to.bitkit.utils.Logger
import uniffi.bitkitcore.BtBolt11InvoiceState
import uniffi.bitkitcore.BtOrderState
import uniffi.bitkitcore.BtOrderState2
import uniffi.bitkitcore.BtPaymentState
import uniffi.bitkitcore.BtPaymentState2
import uniffi.bitkitcore.CJitStateEnum
import uniffi.bitkitcore.IBtBolt11Invoice
import uniffi.bitkitcore.IBtOnchainTransaction
import uniffi.bitkitcore.IBtOnchainTransactions
import uniffi.bitkitcore.IBtOrder
import uniffi.bitkitcore.IBtPayment
import uniffi.bitkitcore.IDiscount
import uniffi.bitkitcore.ILspNode
import uniffi.bitkitcore.IcJitEntry

@Composable
fun ChannelOrdersScreen(
    onBackClick: () -> Unit,
    onOrderItemClick: (String) -> Unit,
    onCjitItemClick: (String) -> Unit,
) {
    val blocktank = blocktankViewModel ?: return
    val orders: MutableList<IBtOrder> = blocktank.orders
    val cJitEntries: MutableList<IcJitEntry> = blocktank.cJitEntries

    LaunchedEffect(Unit) {
        blocktank.refreshOrders()
    }

    ChannelOrdersView(
        orders = orders,
        cJitEntries = cJitEntries,
        onBackClick = onBackClick,
        onOrderItemClick = onOrderItemClick,
        onCjitItemClick = onCjitItemClick,
    )
}

@Composable
private fun ChannelOrdersView(
    orders: MutableList<IBtOrder>,
    cJitEntries: MutableList<IcJitEntry>,
    onBackClick: () -> Unit,
    onOrderItemClick: (String) -> Unit,
    onCjitItemClick: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopBar(
                titleText = "Channel Orders",
                onBackClick = onBackClick,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding)
        ) {
            item {
                Text(
                    text = "Orders",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            orders.let { orders ->
                if (orders.isEmpty()) {
                    item {
                        Text(
                            text = "No orders found…",
                            color = Color.Gray,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(orders) { order ->
                        Card(
                            colors = cardColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clickable { onOrderItemClick(order.id) }
                        ) {
                            OrderRow(order = order)
                        }
                    }
                }
            }

            item {
                Text(
                    text = "CJIT Entries",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            cJitEntries.let { entries ->
                if (entries.isEmpty()) {
                    item {
                        Text(
                            text = "No CJIT entries found…",
                            color = Color.Gray,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(entries) { entry ->
                        Card(
                            colors = cardColors,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp)
                                .clickable { onCjitItemClick(entry.id) }
                        ) {
                            CJitRow(entry = entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OrderDetailScreen(
    orderItem: Routes.OrderDetail,
    onBackClick: () -> Unit,
) {
    val blocktank = blocktankViewModel ?: return
    val order = blocktank.orders.find { it.id == orderItem.id } ?: return
    OrderDetailView(
        order = order,
        onBackClick = onBackClick,
    )
}

@Composable
private fun OrderDetailView(
    order: IBtOrder,
    onBackClick: () -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            AppTopBar(
                titleText = "Order Details",
                onBackClick = onBackClick,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding)
        ) {
            // Order Details
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Order Details",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow("ID", order.id)
                        DetailRow("Onchain txs", order.payment.onchain.transactions.size.toString())
                        DetailRow("State", order.state.toString())
                        DetailRow("State 2", order.state2.toString())
                        DetailRow("LSP Balance", "${order.lspBalanceSat} sats")
                        DetailRow("Client Balance", "${order.clientBalanceSat} sats")
                        DetailRow("Total Fee", "${order.feeSat} sats")
                        DetailRow("Network Fee", "${order.networkFeeSat} sats")
                        DetailRow("Service Fee", "${order.serviceFeeSat} sats")
                    }
                }
            }

            // Channel Settings
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Channel Settings",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
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
            }

            // LSP Information
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "LSP Information",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow("Alias", order.lspNode.alias)
                        DetailRow("Node ID", order.lspNode.pubkey)
                        order.lnurl?.let {
                            DetailRow("LNURL", it)
                        }
                    }
                }
            }

            // Discount Section
            order.couponCode?.let { couponCode ->
                item {
                    Card(
                        colors = cardColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Discount",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DetailRow("Coupon Code", couponCode)
                            order.discount?.let { discount ->
                                DetailRow("Discount Type", discount.code)
                                DetailRow("Value", "${discount.absoluteSat}")
                            }
                        }
                    }
                }
            }

            // Timestamps Section
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Timestamps",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow("Created", order.createdAt)
                        DetailRow("Updated", order.updatedAt)
                    }
                }
            }

            // Open Channel Button
            if (order.state2 == BtOrderState2.PAID) {
                item {
                    val blocktank = blocktankViewModel ?: return@item
                    PrimaryButton(
                        text = "Open Channel",
                        onClick = {
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
    onBackClick: () -> Unit,
) {
    val blocktank = blocktankViewModel ?: return
    val entry = blocktank.cJitEntries.find { it.id == cjitItem.id } ?: return
    CJitDetailView(
        entry = entry,
        onBackClick = onBackClick,
    )
}

@Composable
private fun CJitDetailView(
    entry: IcJitEntry,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppTopBar(
                titleText = "CJIT Entry Details",
                onBackClick = onBackClick,
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding)
        ) {
            // Entry Details Section
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Entry Details",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(label = "ID", value = entry.id)
                        DetailRow(label = "State", value = entry.state.toString())
                        DetailRow(label = "Channel Size", value = "${entry.channelSizeSat} sats")
                        entry.channelOpenError?.let { error ->
                            DetailRow(label = "Error", value = error, isError = true)
                        }
                    }
                }
            }

            // Fees Section
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Fees",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(label = "Total Fee", value = "${entry.feeSat} sats")
                        DetailRow(label = "Network Fee", value = "${entry.networkFeeSat} sats")
                        DetailRow(label = "Service Fee", value = "${entry.serviceFeeSat} sats")
                    }
                }
            }

            // Channel Settings Section
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Channel Settings",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(label = "Node ID", value = entry.nodeId)
                        DetailRow(label = "Expiry Weeks", value = "${entry.channelExpiryWeeks}")
                    }
                }
            }

            // LSP Information Section
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "LSP Information",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(label = "Alias", value = entry.lspNode.alias)
                        DetailRow(label = "Node ID", value = entry.lspNode.pubkey)
                    }
                }
            }

            // Discount Section
            if (entry.couponCode.isNotEmpty()) {
                item {
                    Card(
                        colors = cardColors,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Discount",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            DetailRow(label = "Coupon Code", value = entry.couponCode)
                            entry.discount?.let { discount ->
                                DetailRow(label = "Discount Type", value = discount.code)
                                DetailRow(label = "Value", value = "${discount.absoluteSat}")
                            }
                        }
                    }
                }
            }

            // Timestamps Section
            item {
                Card(
                    colors = cardColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Timestamps",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        DetailRow(label = "Created", value = entry.createdAt)
                        DetailRow(label = "Updated", value = entry.updatedAt)
                        DetailRow(label = "Expires", value = entry.expiresAt)
                    }
                }
            }
        }
    }
}

// region Helpers

private val cardColors: CardColors
    @Composable get() = CardDefaults.cardColors(containerColor = Colors.White10)

@Composable
private fun CopyableText(text: String) {
    val clipboardManager = LocalClipboardManager.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    val coroutineScope = rememberCoroutineScope()

    Text(
        text = text,
        fontSize = if (text.length > 20) 10.sp else 12.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .scale(scale)
            .clickable {
                clipboardManager.setText(AnnotatedString(text))
                coroutineScope.launch {
                    isPressed = true
                    delay(100)
                    isPressed = false
                }
            }
    )
}

@Composable
private fun OrderRow(order: IBtOrder) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CopyableText(text = order.id)
            Surface(
                color = Colors.White16,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = order.state2.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BalanceInfo(label = "LSP Balance", value = "${order.lspBalanceSat.formatWithDotSeparator()} sats")
            BalanceInfo(
                label = "Client Balance",
                value = "${order.clientBalanceSat.formatWithDotSeparator()} sats",
                alignment = Alignment.End
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BalanceInfo(label = "Fees", value = "${order.feeSat.formatWithDotSeparator()} sats")
            BalanceInfo(
                label = "Expires",
                value = order.channelExpiresAt.take(10),
                alignment = Alignment.End
            )
        }
    }
}

@Composable
private fun CJitRow(entry: IcJitEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            CopyableText(text = entry.id)
            Surface(
                color = Colors.White16,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = entry.state.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(4.dp)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            BalanceInfo(label = "Channel Size", value = "${entry.channelSizeSat.formatWithDotSeparator()} sats")
            BalanceInfo(
                label = "Fees",
                value = "${entry.feeSat.formatWithDotSeparator()} sats",
                alignment = Alignment.End
            )
        }

        entry.channelOpenError?.let { error ->
            Text(
                text = error,
                fontSize = if (error.length > 50) 10.sp else 12.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Text(
            text = "Expires: ${entry.expiresAt.take(10)}",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
    }
}

@Composable
private fun BalanceInfo(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(horizontalAlignment = alignment) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, isError: Boolean = false) {
    val clipboardManager = LocalClipboardManager.current
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    val coroutineScope = rememberCoroutineScope()

    val fontSize = when {
        value.length > 40 -> 11.sp
        value.length > 30 -> 12.sp
        else -> 13.sp
    }

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        BodyS(
            text = label,
            color = Colors.White64,
        )
        Text(
            text = value,
            fontSize = fontSize,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier
                .scale(scale)
                .clickable {
                    clipboardManager.setText(AnnotatedString(value))
                    coroutineScope.launch {
                        isPressed = true
                        delay(100)
                        isPressed = false
                    }
                }
        )
    }
}

// endregion

// region Preview

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
private fun ChannelOrdersViewPreview() {
    AppThemeSurface {
        ChannelOrdersView(
            orders = mutableListOf(order),
            cJitEntries = mutableListOf(cjitEntry),
            onBackClick = { },
            onOrderItemClick = { },
            onCjitItemClick = { },
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun OrderDetailViewPreview() {
    AppThemeSurface {
        OrderDetailView(
            order = order,
            onBackClick = { },
        )
    }
}

@Preview(showSystemUi = true)
@Composable
private fun CJitDetailViewPreview() {
    AppThemeSurface {
        CJitDetailView(
            entry = cjitEntry,
            onBackClick = { },
        )
    }
}

// endregion
