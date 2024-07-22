package to.bitkit.ldk

import android.util.Log
import org.ldk.structs.Filter
import org.ldk.structs.WatchedOutput
import to.bitkit._LDK
import to.bitkit.data.WatchedTransaction
import to.bitkit.ext.toHex

object LdkFilter : Filter.FilterInterface {
    var txIds = arrayOf<ByteArray>()
    var outputs = arrayOf<WatchedOutput>()

    override fun register_tx(txid: ByteArray, scriptPubkey: ByteArray) {
        Log.d(_LDK, "LdkTxFilter: register_tx")

        txIds += txid
        val txIdHex = txid.reversedArray().toHex()
        val scriptPubkeyHex = scriptPubkey.toHex()

        val json = JsonBuilder()
            .put("txid", txIdHex)
            .put("script_pubkey", scriptPubkeyHex)
        json.persist("$ldkDir/events_register_tx")
        Ldk.Events.registerTx += json.toString()

        Ldk.Relevant.txs += WatchedTransaction(txid, scriptPubkey)

        Log.d(_LDK, "Relevant LDK txs updated:\n" + Ldk.Relevant.txs.toString())
    }

    override fun register_output(output: WatchedOutput) {
        Log.d(_LDK, "LdkTxFilter: register_output")

        outputs += output
        val index = output._outpoint._index.toString()
        val scriptPubkey = output._script_pubkey.toHex()

        val json = JsonBuilder()
            .put("index", index)
            .put("script_pubkey", scriptPubkey)
        json.persist("$ldkDir/events_register_output")

        Ldk.Events.registerOutput += json.toString()
        Ldk.Relevant.outputs += WatchedOutput.of(
            output._block_hash,
            output._outpoint,
            output._script_pubkey
        )

        Log.d(_LDK, "Relevant LDK outputs updated:\n" + Ldk.Relevant.outputs.toString())
    }
}