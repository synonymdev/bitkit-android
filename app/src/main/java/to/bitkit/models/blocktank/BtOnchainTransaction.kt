package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class BtOnchainTransaction(
    val amountSat: Int,
    val txId: String,
    val vout: Int,
    val blockHeight: Int? = null,
    val blockConfirmationCount: Int,
    val feeRateSatPerVbyte: Int,
    val confirmed: Boolean,
    val suspicious0ConfReason: String,
)
