package to.bitkit.models.blocktank

import kotlinx.serialization.Serializable

@Serializable
data class BtOnchainTransactions(
    val address: String,
    val confirmedSat: Int,
    val requiredConfirmations: Int,
    val transactions: List<BtOnchainTransaction>,
)
