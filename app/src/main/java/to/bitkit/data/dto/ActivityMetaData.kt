package to.bitkit.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ActivityMetaData {
    @Serializable
    @SerialName("onchain")
    data class OnChainActivity(
        val txId: String,
        val feeRate: UInt,
        val address: String,
        val isTransfer: Boolean,
        val channelId: String?,
        val transferTxId: String?,
    ) : ActivityMetaData
}

fun ActivityMetaData.rawId() = when(this) {
    is ActivityMetaData.OnChainActivity -> txId
}
