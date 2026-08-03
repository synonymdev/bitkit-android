package to.bitkit.data.dto

import kotlinx.serialization.Serializable

/**
 * A savings -> spending transfer in bark mode, which takes two on-chain steps: an ldk-node send into
 * bark's own on-chain wallet, then a board of that amount onto Ark once it has confirmed.
 *
 * Persisted so the second step survives the app being killed between them; without it the sats
 * would sit in bark's on-chain wallet, invisible in both balances.
 */
@Serializable
data class PendingBarkBoard(
    /** Txid of the ldk-node send that funds bark's on-chain wallet. */
    val fundingTxId: String,
    val amountSats: ULong,
    val createdAtMillis: Long,
)
