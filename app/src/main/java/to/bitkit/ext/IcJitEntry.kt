package to.bitkit.ext

import uniffi.bitkitcore.BtBolt11InvoiceState
import uniffi.bitkitcore.CJitStateEnum
import uniffi.bitkitcore.IBtBolt11Invoice
import uniffi.bitkitcore.IBtChannel
import uniffi.bitkitcore.ILspNode
import uniffi.bitkitcore.IcJitEntry

fun IcJitEntry.Companion.mock(
    state: CJitStateEnum = CJitStateEnum.CREATED,
    channelSizeSat: ULong = 100_000u,
    feeSat: ULong = 1000u,
    channelExpiryWeeks: UInt = 6u,
    channel: IBtChannel? = null
) = IcJitEntry(
    id = "test-cjit-id",
    state = state,
    feeSat = feeSat,
    networkFeeSat = 500u,
    serviceFeeSat = 500u,
    channelSizeSat = channelSizeSat,
    channelExpiryWeeks = channelExpiryWeeks,
    channelOpenError = null,
    nodeId = "node-id-123456",
    invoice = IBtBolt11Invoice(
        request = "lnbc100000...",
        state = BtBolt11InvoiceState.PENDING,
        expiresAt = "2024-10-28T12:00:00Z",
        updatedAt = "2024-10-21T12:00:00Z"
    ),
    channel = channel,
    lspNode = ILspNode(
        alias = "Test LSP",
        pubkey = "lsp-pubkey-123456",
        connectionStrings = listOf("127.0.0.1:9735"),
        readonly = null
    ),
    couponCode = "",
    source = "bitkit-android",
    discount = null,
    expiresAt = "2024-10-28T12:00:00Z",
    updatedAt = "2024-10-21T12:00:00Z",
    createdAt = "2024-10-21T12:00:00Z"
)
