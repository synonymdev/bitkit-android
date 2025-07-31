package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class TransactionMetadata(
    val txId: String,
    val feeRate: UInt,
    val address: String,
    val isTransfer: Boolean,
    val channelId: String?,
    val transferTxId: String?,
)
