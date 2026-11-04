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

/**
 * Whether a hardware broadcast proves it sent nothing: core could not decode the transaction or connect to the
 * server. Any other failure may follow the write, and a node that refused the transaction still saw it, so the payment
 * may still confirm.
 */
fun Throwable.isHardwareBroadcastNotSent(): Boolean = isDefiniteHardwarePreBroadcastFailure() ||
    generateSequence(this) { it.cause }.any {
        it is BroadcastException.ElectrumException && it.errorDetails.startsWith("Failed to connect to Electrum: ")
    }

/**
 * Whether a hardware broadcast proves its transaction can never confirm: core could not decode it, or the server's node
 * refused it for breaking consensus or script rules. A relay policy refusal, such as a fee, mempool, dust or
 * standardness one, or an unreadable refusal is no proof, as another node or miner may still confirm the same
 * transaction.
 */
fun Throwable.isHardwareBroadcastNeverConfirmable(): Boolean = isDefiniteHardwarePreBroadcastFailure() ||
    serverRefusalMessages().any { message ->
        message.refusalLines().any { line -> NEVER_CONFIRMABLE_REFUSALS.any { line.isRefusal(it) } }
    }

/** Whether the server's node refused a hardware broadcast because one of its coins is missing or already spent. */
fun Throwable.isHardwareBroadcastInputsSpent(): Boolean = serverRefusalMessages().any { message ->
    message.refusalLines().any { it.isRefusal("bad-txns-inputs-missingorspent") }
}

// A recognized node refusal permits leaving the sheet. It never proves that an earlier, uncertain dispatch of the
// same bytes was not delivered, so it never releases a Shop payment's guard.
fun Throwable.isHardwareBroadcastRefusalForNavigation(): Boolean =
    isDefiniteHardwarePreBroadcastFailure() || hardwareRefusalMessages().any { it.isKnownHardwareRefusal() }

private val NEVER_CONFIRMABLE_REFUSALS = listOf(
    "mandatory-script-verify-flag-failed",
    "block-script-verify-flag-failed",
    "bad-txns-in-belowout",
    "bad-txns-inputs-duplicate",
    "bad-txns-vout-negative",
    "bad-txns-vout-toolarge",
    "bad-txns-txouttotal-toolarge",
    "bad-txns-oversize",
)

// The refusal message of a node, from core's Electrum broadcast error or the server's JSON or RPC wrapping of it
private fun Throwable.hardwareRefusalMessages(): List<String> = generateSequence(this) { it.cause }
    .filterIsInstance<BroadcastException.ElectrumException>()
    .mapNotNull { it.errorDetails.refusalMessage()?.refusalReason() }
    .toList()

// Only a refusal the server returned as JSON is read as one; any other wording proves nothing
private fun Throwable.serverRefusalMessages(): List<String> = generateSequence(this) { it.cause }
    .filterIsInstance<BroadcastException.ElectrumException>()
    .mapNotNull { error ->
        val prefix = "broadcast failed: electrum server error: "
        if (!error.errorDetails.startsWith(prefix, ignoreCase = true)) return@mapNotNull null
        refusalMessageFromJson(error.errorDetails.drop(prefix.length))?.refusalReason()
    }
    .toList()

private fun String.refusalLines(): Sequence<String> = lineSequence().map { it.trim().lowercase() }

private fun String.refusalMessage(): String? {
    val prefix = "broadcast failed: "
    if (!startsWith(prefix, ignoreCase = true)) return null
    val reason = drop(prefix.length)
    val serverPrefix = "electrum server error: "
    if (!reason.startsWith(serverPrefix, ignoreCase = true)) return reason
    return refusalMessageFromJson(reason.drop(serverPrefix.length))
}

private fun String.refusalReason(): String? {
    val rpcPrefix = "sendrawtransaction RPC error: "
    if (startsWith(rpcPrefix, ignoreCase = true)) return refusalMessageFromJson(drop(rpcPrefix.length))
    if (!startsWith("sendrawtransaction RPC error ", ignoreCase = true)) return this
    val coded = drop("sendrawtransaction RPC error ".length)
    val code = coded.substringBefore(": ")
    if (code !in listOf("-25", "-26") || !coded.startsWith("$code: ")) return null
    return coded.drop(code.length + 2)
}

private fun String.isRefusal(known: String): Boolean =
    this == known || startsWith("$known,") || startsWith("$known ") || startsWith("$known(")

private fun refusalMessageFromJson(payload: String): String? = runCatching {
    when (val element = Json.parseToJsonElement(payload)) {
        is JsonPrimitive -> element.takeIf { it.isString }?.contentOrNull
        is JsonObject -> (element["message"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        else -> null
    }
}.getOrNull()

private fun String.isKnownHardwareRefusal(): Boolean {
    // Match the complete Bitcoin Core replacement refusal; unknown suffixes remain guarded.
    val replacementRefusal = Regex(
        """insufficient fee, rejecting replacement [0-9a-f]{64}; new feerate """ +
            """[0-9]+\.[0-9]{8} btc/kvb <= old feerate [0-9]+\.[0-9]{8} btc/kvb""",
    )
    if (replacementRefusal.matches(lowercase())) return true
    val reasons = listOf(
        "min relay fee not met",
        "mempool min fee not met",
        "bad-txns-inputs-missingorspent",
        "txn-mempool-conflict",
        "non-final",
    )
    return refusalLines().any { line -> reasons.any { line.isRefusal(it) && !line.startsWith("$it(") } }
}
