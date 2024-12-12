package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class RegtestMineRequest(
    val count: Int
)

@Serializable
data class RegtestDepositRequest(
    val address: String,
    val amountSat: Int
)

@Serializable
data class RegtestPayRequest(
    val invoice: String,
    val amountSat: Int? = null
)

@Serializable
data class RegtestCloseChannelRequest(
    val fundingTxId: String,
    val vout: Int,
    val forceCloseAfterS: Int = 86400
)
