package to.bitkit.data.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Raw block info response from Mempool.space API
 */
@Serializable
data class MempoolBlockInfo(
    val id: String,
    val height: Long,
    val timestamp: Long,
    @SerialName("tx_count") val txCount: Int,
    val size: Long,
    val weight: Long,
    val difficulty: Double,
    @SerialName("merkle_root") val merkleRoot: String
)
