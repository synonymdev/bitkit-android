package to.bitkit.ext

import com.synonym.bitkitcore.BroadcastException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
        val details = it.errorDetails
        val prefix = "broadcast failed: "
        if (!details.startsWith(prefix, ignoreCase = true)) return@any false
        val reason = details.drop(prefix.length)
        val serverPrefix = "electrum server error: "
        val message = if (reason.startsWith(serverPrefix, ignoreCase = true)) {
            refusalMessageFromJson(reason.drop(serverPrefix.length)) ?: return@any false
        } else {
            reason
        }
        message.isKnownHardwareRefusal()
    }

private fun refusalMessageFromJson(payload: String): String? = runCatching {
    when (val element = Json.parseToJsonElement(payload)) {
        is JsonPrimitive -> element.takeIf { it.isString }?.contentOrNull
        is JsonObject -> (element["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        else -> null
    }
}.getOrNull()

private fun String.isKnownHardwareRefusal(): Boolean {
    val rpcPrefix = "sendrawtransaction RPC error: "
    val message = if (startsWith(rpcPrefix, ignoreCase = true)) {
        refusalMessageFromJson(drop(rpcPrefix.length)) ?: return false
    } else if (startsWith("sendrawtransaction RPC error ", ignoreCase = true)) {
        val coded = drop("sendrawtransaction RPC error ".length)
        val code = coded.substringBefore(": ")
        if (code !in listOf("-25", "-26") || !coded.startsWith("$code: ")) return false
        coded.drop(code.length + 2)
    } else {
        this
    }
    // Match the complete Bitcoin Core replacement refusal; unknown suffixes remain guarded.
    val replacementRefusal = Regex(
        """insufficient fee, rejecting replacement [0-9a-f]{64}; new feerate """ +
            """[0-9]+\.[0-9]{8} btc/kvb <= old feerate [0-9]+\.[0-9]{8} btc/kvb""",
    )
    if (replacementRefusal.matches(message.lowercase())) return true
    val reasons = listOf(
        "min relay fee not met",
        "mempool min fee not met",
        "bad-txns-inputs-missingorspent",
        "txn-mempool-conflict",
        "non-final",
    )
    return message.lineSequence().any { line ->
        val reason = line.trim().lowercase()
        reasons.any { known -> reason == known || reason.startsWith("$known,") || reason.startsWith("$known ") }
    }
}
