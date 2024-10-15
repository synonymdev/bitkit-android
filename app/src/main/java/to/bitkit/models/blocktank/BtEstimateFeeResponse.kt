package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class BtEstimateFeeResponse(
    val feeSat: Int,
    val min0ConfTxFee: Bt0ConfMinTxFeeWindow,
)
