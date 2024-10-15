package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class BtPayment(
    /**
     * deprecated: Will be removed in future releases. Use state2 instead.
     */
    val state: BtPaymentState,
    val state2: BtPaymentState2,
    val paidSat: Int,
    val bolt11Invoice: BtBolt11Invoice,
    val onchain: BtOnchainTransactions,
    val isManuallyPaid: Boolean? = null,
)
