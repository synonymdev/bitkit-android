package to.bitkit.models.blocktank

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Bt0ConfMinTxFeeWindow(
    /**
     * Minimum fee rate that is required for Blocktank to accept the onchain payment to be 0conf.
     */
    val satPerVByte: Int,

    /**
     * How long this fee rate is valid for.
     */
    val validityEndsAt: Instant,
)
