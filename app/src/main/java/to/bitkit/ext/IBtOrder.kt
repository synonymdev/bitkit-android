package to.bitkit.ext

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

@Suppress("SpellCheckingInspection")
fun mockOrder(): IBtOrder {
    return IBtOrder(
        id = "orderId",
        state = BtOrderState.CREATED,
        state2 = BtOrderState2.CREATED,
        feeSat = 1000u,
        networkFeeSat = 500u,
        serviceFeeSat = 500u,
        lspBalanceSat = 100000u,
        clientBalanceSat = 50000u,
        zeroConf = false,
        zeroReserve = false,
        clientNodeId = "03f1a4c7e2b5d8a9c6f3e0b7a2d5c8e1f4a7b0c3d6e9f2a5b8c1d4e7f0a3b6c9",
        channelExpiryWeeks = 52u,
        channelExpiresAt = "2025-01-15T10:30:00.000Z",
        orderExpiresAt = "2024-02-15T10:30:00.000Z",
        channel = null,
        lspNode = ILspNode(
            alias = "Synonym LSP",
            pubkey = "03e1b4a7c0d3f6a9c2e5b8d1f4a7b0c3d6e9f2a5b8c1d4e7f0a3b6c9e2f5a8",
            connectionStrings = listOf("lsp.synonym.to:9735"),
            readonly = false,
        ),
        lnurl = null,
        payment = IBtPayment(
            state = BtPaymentState.CREATED,
            state2 = BtPaymentState2.CREATED,
            paidSat = 0u,
            bolt11Invoice = IBtBolt11Invoice(
                request = "lnbc10u1p3x2dq5pp5q0s3rz4q8n7z2k5x8v1w4t7y0u3r6s9v2x5z8w1t4y7u0r3s6w9",
                state = BtBolt11InvoiceState.PENDING,
                expiresAt = "2024-01-16T10:30:00.000Z",
                updatedAt = "2024-01-15T10:30:00.000Z",
            ),
            onchain = IBtOnchainTransactions(
                address = "bc1qxy2kgdygjrsqtzq2n0yrf2493p83kkfjhx0wlh",
                confirmedSat = 0u,
                requiredConfirmations = 1u,
                transactions = emptyList(),
            ),
            isManuallyPaid = false,
            manualRefunds = null,
        ),
        couponCode = null,
        source = "bitkit-android",
        discount = null,
        updatedAt = "2024-01-15T10:30:00.000Z",
        createdAt = "2024-01-15T10:30:00.000Z",
    )
}
