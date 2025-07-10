package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PendingBoostActivity(
    val txId: String,
    val feeRate: ULong,
    val fee: ULong,
    val updatedAt: ULong,
    val activityToDelete: String?
)
