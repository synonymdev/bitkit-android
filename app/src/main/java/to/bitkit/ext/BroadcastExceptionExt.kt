package to.bitkit.ext

import com.synonym.bitkitcore.BroadcastException
import kotlinx.coroutines.TimeoutCancellationException

fun Throwable.isBroadcastConnectivityFailure(): Boolean =
    generateSequence(this) { it.cause }.any {
        it is TimeoutCancellationException || it is BroadcastException.ElectrumException
    }

fun Throwable.isDefiniteHardwarePreBroadcastFailure(): Boolean = generateSequence(this) { it.cause }.any {
    it is BroadcastException.InvalidHex || it is BroadcastException.InvalidTransaction
}

// A recognized node refusal permits leaving the sheet, but never proves non-delivery.
fun Throwable.isHardwareBroadcastRefusalForNavigation(): Boolean =
    isDefiniteHardwarePreBroadcastFailure() || generateSequence(this) { it.cause }.any {
        if (it !is BroadcastException.ElectrumException) return@any false
        val details = it.errorDetails.lowercase()
        if (!details.startsWith("broadcast failed: ")) return@any false
        val reason = details.removePrefix("broadcast failed: ")
        reason == "min relay fee not met" || reason == "mempool min fee not met" ||
            reason == "bad-txns-inputs-missingorspent" || reason == "txn-mempool-conflict" ||
            reason == "non-final"
    }
