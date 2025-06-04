package to.bitkit.data.dto

import kotlinx.serialization.Serializable

@Serializable
data class BlockDTO(
    val hash: String,
    val height: String,
    val timestamp: Long,
    val transactionCount: String,
    val size: String,
    val weight: String,
    val difficulty: String,
    val merkleRoot: String,
    val source: String,
)
