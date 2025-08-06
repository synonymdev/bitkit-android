package to.bitkit.models

import kotlinx.serialization.Serializable

@Serializable
data class PaidOrderModel(
    val orderId: String,
    val txId: String,
    val channelSetupStep: ChannelSetupStep
)
