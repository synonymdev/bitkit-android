package to.bitkit.ui.screens.transfer

import com.synonym.bitkitcore.BtBolt11InvoiceState
import com.synonym.bitkitcore.BtOrderState
import com.synonym.bitkitcore.BtOrderState2
import com.synonym.bitkitcore.BtPaymentState
import com.synonym.bitkitcore.BtPaymentState2
import com.synonym.bitkitcore.IBtBolt11Invoice
import com.synonym.bitkitcore.IBtOnchainTransactions
import com.synonym.bitkitcore.IBtOrder
import com.synonym.bitkitcore.IBtPayment
import com.synonym.bitkitcore.ILspNode

internal fun previewBtOrder(
    networkFeeSat: ULong = 2_483UL,
    serviceFeeSat: ULong = 1_520UL,
    clientBalanceSat: ULong = 967_724UL,
    feeSat: ULong = 971_727UL,
): IBtOrder = IBtOrder(
    id = "order_7e6f3b7c-486a-4f5a-8b1e-2c9d7f0a8b9d",
    state = BtOrderState.CREATED,
    state2 = BtOrderState2.CREATED,
    feeSat = feeSat,
    networkFeeSat = networkFeeSat,
    serviceFeeSat = serviceFeeSat,
    lspBalanceSat = 2_000_000UL,
    clientBalanceSat = clientBalanceSat,
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
        readonly = null,
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
            updatedAt = "2025-07-28T08:30:00Z",
        ),
        onchain = IBtOnchainTransactions(
            address = "bc1qar0srrr7xfkvy5l643lydnw9re59gtzzwf5mdq",
            confirmedSat = 0UL,
            requiredConfirmations = 1u,
            transactions = emptyList(),
        ),
        isManuallyPaid = null,
        manualRefunds = null,
    ),
    couponCode = null,
    source = null,
    discount = null,
    updatedAt = "2025-07-28T08:29:03Z",
    createdAt = "2025-07-28T08:29:03Z",
)
