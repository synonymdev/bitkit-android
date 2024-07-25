package to.bitkit.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Tx(
    val txid: String,
    val status: TxStatus,
)

@Serializable
data class TxStatus(
    @SerialName("confirmed")
    val isConfirmed: Boolean,
    @SerialName("block_height")
    val blockHeight: Int? = null,
    @SerialName("block_hash")
    val blockHash: String? = null,
)

@Serializable
data class OutputSpent(
    val spent: Boolean,
)

@Serializable
data class MerkleProof(
    @SerialName("block_height")
    val blockHeight: Int,
    @Suppress("ArrayInDataClass")
    val merkle: Array<String>,
    val pos: Int,
)
