package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class PendingBoostActivity(
    val txId: String,
    val updatedAt: ULong,
    val activityToDelete: String?,
    val boostTxIds: List<String> = emptyList(),
)
