package to.bitkit.ldk

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitcoindevkit.Transaction
import org.ldk.structs.BroadcasterInterface
import to.bitkit._LDK
import to.bitkit.bdk.Bdk
import to.bitkit.ext.toHex

object LdkBroadcaster : BroadcasterInterface.BroadcasterInterfaceInterface {
    @OptIn(ExperimentalUnsignedTypes::class)
    override fun broadcast_transactions(txs: Array<out ByteArray>?) {
        txs?.let { transactions ->
            CoroutineScope(Dispatchers.IO).launch {
                transactions.forEach { txByteArray ->
                    val uByteArray = txByteArray.toUByteArray()
                    val transaction = Transaction(uByteArray.toList())

                    Bdk.broadcastRawTx(transaction)
                    Log.d(_LDK, "Broadcasted raw tx: ${txByteArray.toHex()}")
                }
            }
        } ?: throw (IllegalStateException("Broadcaster error: can't broadcast a null transaction"))
    }
}