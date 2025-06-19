package to.bitkit.ext

import org.lightningdevkit.ldknode.SpendableUtxo

fun SpendableUtxo.uniqueUtxoKey(): String {
    return "${outpoint.txid}_${outpoint.vout}"
}
