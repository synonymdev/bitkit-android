package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
sealed interface ActivityMetaData {
    data class OnChainActivity(
        val txId: String,
        val feeRate: UInt,
        val address: String,
        val isTransfer: Boolean,
        val channelId: String?,
        val transferTxId: String?,
    ) : ActivityMetaData

    data class Bolt11(
        val paymentId: String,
        val invoice: String,
    ) : ActivityMetaData
}

fun ActivityMetaData.rawId() = when(this) {
    is ActivityMetaData.Bolt11 -> paymentId
    is ActivityMetaData.OnChainActivity -> txId
}
