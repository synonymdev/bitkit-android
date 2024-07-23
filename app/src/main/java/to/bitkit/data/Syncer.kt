package to.bitkit.data

import android.util.Log
import org.ldk.structs.TwoTuple_usizeTransactionZ
import to.bitkit._DEV
import to.bitkit._LDK
import to.bitkit.bdk.Bdk
import to.bitkit.ext.toByteArray
import to.bitkit.ext.toHex
import to.bitkit.ldk.Ldk
import to.bitkit.ldk.LdkEventHandler
import to.bitkit.ldk.LdkFilter
import javax.inject.Inject

interface Syncer {
    suspend fun sync()
}

class LdkSyncer @Inject constructor(
    private val restApi: RestApi,
) : Syncer {
    override suspend fun sync() {
        Log.d(_DEV, "BDK & LDK syncing…")

        Bdk.sync()

        val channelManager = Ldk.channelManager
        val chainMonitor = Ldk.chainMonitor

        val confirmedTxs = mutableListOf<ConfirmedTx>()

        // Sync unconfirmed transactions
        val relevantTxIds = Ldk.Relevant.txs.map { it.id.reversedArray().toHex() }
        Log.d(_DEV, "Syncing '${relevantTxIds.size}' relevant txs…")

        for (txId in relevantTxIds) {
            Log.d(_DEV, "Checking relevant tx confirmation status: $txId")
            val tx: Tx = restApi.getTx(txId)
            if (tx.status.isConfirmed) {
                Log.d(_DEV, "Adding confirmed TX")
                val txHex = restApi.getTxHex(txId)
                val blockHeader = restApi.getHeader(requireNotNull(tx.status.blockHash))
                val merkleProof = restApi.getMerkleProof(txId)
                if (tx.status.blockHeight == merkleProof.blockHeight) {
                    Log.d(_DEV, "Caching confirmed TX")
                    confirmedTxs += ConfirmedTx(
                        tx = txHex.toByteArray(),
                        blockHeight = tx.status.blockHeight,
                        blockHeader = blockHeader,
                        merkleProofPos = merkleProof.pos,
                    )
                }
            } else {
                Log.d(_LDK, "Marking unconfirmed TX")
                channelManager.as_Confirm().transaction_unconfirmed(txId.toByteArray())
                chainMonitor.as_Confirm().transaction_unconfirmed(txId.toByteArray())
            }
        }

        // Add confirmed Tx from filter Transaction Output
        val relevantOutputs = Ldk.Relevant.outputs
        if (relevantOutputs.isNotEmpty()) {
            for (output in relevantOutputs) {
                val outpoint = output._outpoint
                val txId = outpoint._txid.reversedArray().toHex()
                val outputSpent = restApi.getOutputSpent(txId, outpoint._index.toInt())
                if (outputSpent.spent) {
                    val tx = restApi.getTx(txId)
                    if (tx.status.isConfirmed) {
                        val txHex = restApi.getTxHex(txId)
                        val blockHeader = restApi.getHeader(requireNotNull(tx.status.blockHash))
                        val merkleProof = restApi.getMerkleProof(txId)
                        if (tx.status.blockHeight == merkleProof.blockHeight) {
                            confirmedTxs += ConfirmedTx(
                                tx = txHex.toByteArray(),
                                blockHeight = tx.status.blockHeight,
                                blockHeader = blockHeader,
                                merkleProofPos = merkleProof.pos
                            )
                        }
                    }
                }

            }
        }

        // Add confirmed Tx from filtered Transaction Ids
        val filteredTxs = LdkFilter.txIds
        if (filteredTxs.isNotEmpty()) {
            Log.d(_DEV, "Getting Filtered TXs")
            for (txid in filteredTxs) {
                val txId = txid.reversedArray().toHex()
                val tx = restApi.getTx(txId)
                if (tx.status.isConfirmed) {
                    val txHex = restApi.getTxHex(txId)
                    val blockHeader = restApi.getHeader(requireNotNull(tx.status.blockHash))
                    val merkleProof = restApi.getMerkleProof(txId)
                    if (tx.status.blockHeight == merkleProof.blockHeight) {
                        confirmedTxs += ConfirmedTx(
                            tx = txHex.toByteArray(),
                            blockHeight = tx.status.blockHeight,
                            blockHeader = blockHeader,
                            merkleProofPos = merkleProof.pos
                        )
                    }
                }
            }
        }

        // Add confirmed Tx from filter Transaction Output
        val filteredOutputs = LdkFilter.outputs
        if (filteredOutputs.isNotEmpty()) {
            for (output in filteredOutputs) {
                val outpoint = output._outpoint
                val outputIndex = outpoint._index
                val txId = outpoint._txid.reversedArray().toHex()
                val outputSpent = restApi.getOutputSpent(txId, outputIndex.toInt())
                if (outputSpent.spent) {
                    val tx = restApi.getTx(txId)
                    if (tx.status.isConfirmed) {
                        val txHex = restApi.getTxHex(txId)
                        val blockHeader = restApi.getHeader(requireNotNull(tx.status.blockHash))
                        val merkleProof = restApi.getMerkleProof(txId)
                        if (tx.status.blockHeight == merkleProof.blockHeight) {
                            confirmedTxs += ConfirmedTx(
                                tx = txHex.toByteArray(),
                                blockHeight = tx.status.blockHeight,
                                blockHeader = blockHeader,
                                merkleProofPos = merkleProof.pos,
                            )
                        }
                    }
                }
            }
        }

        confirmedTxs.sortWith(
            compareBy<ConfirmedTx> { it.blockHeight }.thenBy { it.merkleProofPos }
        )

        // Sync confirmed transactions
        for (cTx in confirmedTxs) {
            channelManager.as_Confirm().transactions_confirmed(
                cTx.blockHeader.toByteArray(),
                arrayOf(TwoTuple_usizeTransactionZ.of(cTx.merkleProofPos.toLong(), cTx.tx)),
                cTx.blockHeight,
            )

            chainMonitor.as_Confirm().transactions_confirmed(
                cTx.blockHeader.toByteArray(),
                arrayOf(TwoTuple_usizeTransactionZ.of(cTx.merkleProofPos.toLong(), cTx.tx)),
                cTx.blockHeight,
            )
        }

        // Sync best block
        val height = restApi.getLatestBlockHeight()
        val hash = restApi.getLatestBlockHash()
        val header = restApi.getHeader(hash).toByteArray()

        channelManager.as_Confirm().best_block_updated(header, height)
        chainMonitor.as_Confirm().best_block_updated(header, height)

        Ldk.channelManagerConstructor.chain_sync_completed(LdkEventHandler, true)

        Log.d(_DEV, "BDK & LDK synced.")
    }
}