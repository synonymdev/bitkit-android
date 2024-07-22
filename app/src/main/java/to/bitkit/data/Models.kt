package to.bitkit.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

class WatchedTransaction(
    val id: ByteArray,
    @Suppress("unused")
    val scriptPubKey: ByteArray,
)

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

class ConfirmedTx(
    val tx: ByteArray,
    val blockHeight: Int,
    val blockHeader: String,
    val merkleProofPos: Int,
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