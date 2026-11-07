package to.bitkit.repositories

import breez_sdk_spark.RecoveryTransaction
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError
import to.bitkit.utils.SignedTransactionId
import kotlin.math.ceil

data class EsploraTxStatus(val confirmed: Boolean, val blockHeight: UInt? = null)

data class EsploraUtxo(val txid: String, val vout: UInt, val value: ULong, val status: EsploraTxStatus)

/** What a broadcast came to. Accepted includes a transaction the node already had. */
sealed interface SparkExitBroadcastOutcome {
    val summary: String

    data object Accepted : SparkExitBroadcastOutcome {
        override val summary = "accepted"
    }

    /** The host or node could not take it now: the next tick tries again. */
    data class RetryLater(val reason: String) : SparkExitBroadcastOutcome {
        override val summary get() = "$PREFIX$reason"

        companion object {
            const val PREFIX = "retry: "
        }
    }

    /** Its relative timelock has not matured (`non-BIP68-final`): sent again once it has, never a rejection. */
    data class NotMature(val reason: String) : SparkExitBroadcastOutcome {
        override val summary get() = "not mature: $reason"
    }

    /**
     * An input is missing or already spent: its parent is not visible yet, or, once every parent confirmed, someone
     * else (the operators) spent it first. Never a rejection.
     */
    data class InputsMissing(val reason: String) : SparkExitBroadcastOutcome {
        override val summary get() = "inputs missing: $reason"
    }

    /** Refused for good at this fee or policy; a later check or rebuild replaces it. */
    data class Rejected(val reason: String) : SparkExitBroadcastOutcome {
        override val summary get() = "rejected: $reason"
    }
}

/** The chain the Spark SDK reads, for what an exit does outside the SDK: broadcasting and the funding address. */
interface SparkExitChain {
    suspend fun tipHeight(): UInt
    suspend fun utxos(address: String): List<EsploraUtxo>
    suspend fun txStatus(txid: String): EsploraTxStatus

    /**
     * Whether a read host has [txid] in a block or its mempool, from `/tx/{txid}/hex`: false only when every read host
     * answers 404. Throws when a host fails otherwise, since that host may know it.
     */
    suspend fun isTxKnown(txid: String): Boolean
    suspend fun broadcast(tx: RecoveryTransaction): SparkExitBroadcastOutcome

    /**
     * A transaction of the app's own, such as the sweep of leftover fee funding, as a single `/tx`, or as a one-item
     * package on a host that refuses it there with a bare RPC error.
     */
    suspend fun broadcastHex(hex: String): SparkExitBroadcastOutcome

    /** The rate for the next block, from `/fee-estimates`, rounded up. */
    suspend fun nextBlockFeeRate(): ULong

    /** The rates for the next block and about half an hour, from `/fee-estimates` targets 1 and 3, rounded up. */
    suspend fun networkFees(): SparkExitNetworkFees

    /** The network's cheapest rate, `/fee-estimates` at its farthest target, rounded up; 1 sat/vB with none. */
    suspend fun economyFeeRate(): ULong
}

/** The least rate nodes relay, used when the chain host gives no estimate at all. */
private const val MIN_RELAY_RATE = 1uL

/**
 * `/fee-estimates`, a map from confirmation target in blocks to sat/vB. A host can leave targets out, or answer `{}`
 * as a fresh mempool does, so each read falls back to the next target it has.
 */
object SparkExitFeeEstimates {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): Map<Int, Double> = runCatching {
        json.parseToJsonElement(body).jsonObject.mapNotNull { (key, value) ->
            val target = key.toIntOrNull() ?: return@mapNotNull null
            val rate = (value as? JsonPrimitive)?.content?.toDoubleOrNull() ?: return@mapNotNull null
            target to rate
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The rate for [target] blocks, else for the next target after it, else the closest before it; at least 1. */
    fun at(estimates: Map<Int, Double>, target: Int): ULong? {
        val key = estimates.keys.filter { it >= target }.minOrNull() ?: estimates.keys.maxOrNull() ?: return null
        return round(estimates.getValue(key))
    }

    /** The rate at the farthest target, the cheapest the host knows; at least 1. */
    fun farthest(
        estimates: Map<Int, Double>
    ): ULong? = estimates.keys.maxOrNull()?.let { round(estimates.getValue(it)) }

    private fun round(rate: Double): ULong = maxOf(1uL, ceil(rate).toULong())
}

/** What the network asks now, in sat/vB: for the next block, and for about half an hour. */
data class SparkExitNetworkFees(val fastest: ULong, val halfHour: ULong)

class SparkExitChainError(message: String) : AppError(message)

/** A host an exit broadcasts through, with the paths it takes a package on, tried in order. */
data class SparkExitBroadcastHost(
    val url: String,
    val packagePaths: List<String> = listOf("/v1/txs/package", "/txs/package"),
)

/** Where an exit reads the chain, and where it broadcasts, each tried in order. */
data class SparkExitHosts(val reads: List<String>, val broadcasts: List<SparkExitBroadcastHost>) {
    companion object {
        /** One set of hosts for reads and broadcasts alike. */
        fun of(vararg urls: String) = SparkExitHosts(urls.toList(), urls.map(::SparkExitBroadcastHost))
    }
}

/**
 * esplora and mempool.space REST: a transaction with a CPFP child goes out as a package (a JSON array of hex), one
 * without as a single `/tx`. A host that answers `/tx` with a bare `{"code":-1}`, as Spark's hosted regtest does for
 * every lone transaction, gets the same hex again as a one-item package, whose answer decides.
 */
class EsploraSparkExitChain(
    private val hosts: SparkExitHosts,
    private val client: HttpClient,
) : SparkExitChain {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The chain the Spark SDK reads per network. Mainnet reads Bitkit's own esplora alone, which takes a package
         * on `/txs/package` only, and broadcasts there first, then through the SDK's former default hosts.
         */
        fun defaultHosts(sparkNetwork: String?): SparkExitHosts? = when (sparkNetwork) {
            "mainnet" -> SparkExitHosts(
                reads = listOf(Env.bitkitEsploraUrl),
                broadcasts = listOf(
                    SparkExitBroadcastHost(Env.bitkitEsploraUrl, packagePaths = listOf("/txs/package")),
                    SparkExitBroadcastHost("https://mempool.space/api"),
                    SparkExitBroadcastHost("https://blockstream.info/api"),
                ),
            )
            "regtest" -> SparkExitHosts.of("https://regtest-mempool.us-west-2.sparkinfra.net/api")
            else -> null
        }

        private val NOT_HERE = setOf(404, 405)

        private const val NOT_FOUND = 404
    }

    override suspend fun tipHeight(): UInt = get("/blocks/tip/height").trim().toUInt()

    override suspend fun utxos(address: String): List<EsploraUtxo> =
        json.parseToJsonElement(get("/address/$address/utxo")).jsonArray.map {
            val o = it.jsonObject
            EsploraUtxo(
                txid = o.getValue("txid").jsonPrimitive.content,
                vout = o.getValue("vout").jsonPrimitive.content.toUInt(),
                value = o.getValue("value").jsonPrimitive.content.toULong(),
                status = status(o.getValue("status").jsonObject),
            )
        }

    override suspend fun txStatus(txid: String): EsploraTxStatus =
        status(json.parseToJsonElement(get("/tx/$txid/status")).jsonObject)

    override suspend fun nextBlockFeeRate(): ULong = SparkExitFeeEstimates.at(feeEstimates(), 1) ?: MIN_RELAY_RATE

    override suspend fun networkFees(): SparkExitNetworkFees {
        val estimates = feeEstimates()
        return SparkExitNetworkFees(
            fastest = SparkExitFeeEstimates.at(estimates, 1) ?: MIN_RELAY_RATE,
            halfHour = SparkExitFeeEstimates.at(estimates, 3) ?: MIN_RELAY_RATE,
        )
    }

    override suspend fun economyFeeRate(): ULong = SparkExitFeeEstimates.farthest(feeEstimates()) ?: MIN_RELAY_RATE

    private suspend fun feeEstimates(): Map<Int, Double> = SparkExitFeeEstimates.parse(get("/fee-estimates"))

    override suspend fun isTxKnown(txid: String): Boolean {
        if (hosts.reads.isEmpty()) throw SparkExitChainError("No chain host configured")
        val path = "/tx/$txid/hex"
        var lastError: Throwable? = null
        for (host in hosts.reads) {
            val status = runSuspendCatching { client.get("$host$path").status }
                .onFailure { lastError = it }
                .getOrNull()
                ?: continue
            if (status.isSuccess()) return true
            if (status.value != NOT_FOUND) lastError = SparkExitChainError("HTTP '${status.value}' for '$path'")
        }
        lastError?.let { throw it }
        return false
    }

    /** Tries each host until one accepts. Otherwise a node's refusal wins over a host that was only unavailable. */
    override suspend fun broadcast(tx: RecoveryTransaction): SparkExitBroadcastOutcome {
        val outcomes = mutableListOf<SparkExitBroadcastOutcome>()
        for (host in hosts.broadcasts) {
            val child = tx.cpfpTxHex
            val attempt = if (child != null) {
                submitPackage(host, listOf(tx.txHex, child), tx.txid)
            } else {
                submitSingle(host, tx.txHex)
            }
            // null: this host cannot take it (no package endpoint, unreachable).
            if (attempt == SparkExitBroadcastOutcome.Accepted) return attempt
            attempt?.let { outcomes += it }
        }
        return outcomes.firstOrNull { it is SparkExitBroadcastOutcome.Rejected }
            ?: outcomes.firstOrNull()
            ?: SparkExitBroadcastOutcome.RetryLater("no chain host reachable")
    }

    override suspend fun broadcastHex(hex: String): SparkExitBroadcastOutcome {
        val outcomes = hosts.broadcasts.mapNotNull { host ->
            submitSingle(host, hex).also { if (it == SparkExitBroadcastOutcome.Accepted) return it }
        }
        return outcomes.firstOrNull { it is SparkExitBroadcastOutcome.Rejected }
            ?: outcomes.firstOrNull()
            ?: SparkExitBroadcastOutcome.RetryLater("no chain host reachable")
    }

    private suspend fun submitPackage(
        host: SparkExitBroadcastHost,
        hexes: List<String>,
        parentTxid: String?,
    ): SparkExitBroadcastOutcome? {
        val body = JsonArray(hexes.map(::JsonPrimitive)).toString()
        for (path in host.packagePaths) {
            val response = post("${host.url}$path", TextContent(body, ContentType.Application.Json)) ?: return null
            if (response.status.value in NOT_HERE) continue
            return SparkExitBroadcastClassifier.pkg(response.status.value, response.bodyAsText(), parentTxid)
        }
        return null
    }

    private suspend fun submitSingle(host: SparkExitBroadcastHost, hex: String): SparkExitBroadcastOutcome? {
        val response = post("${host.url}/tx", TextContent(hex, ContentType.Text.Plain)) ?: return null
        if (response.status.value in NOT_HERE) return null
        val body = response.bodyAsText()
        val outcome = SparkExitBroadcastClassifier.single(response.status.value, body)
        if (outcome !is SparkExitBroadcastOutcome.Rejected || !SparkExitBroadcastClassifier.isUnplaceable(body)) {
            return outcome
        }
        val txid = runCatching { SignedTransactionId.fromHex(hex) }.getOrNull()
        return submitPackage(host, listOf(hex), txid) ?: outcome
    }

    private suspend fun post(url: String, content: TextContent): HttpResponse? =
        runSuspendCatching {
            client.post(url) {
                // The shared client defaults to JSON; the body carries its own type.
                headers.remove(HttpHeaders.ContentType)
                setBody(content)
            }
        }.getOrNull()

    private suspend fun get(path: String): String {
        var lastError: Throwable = SparkExitChainError("No chain host configured")
        for (host in hosts.reads) {
            runSuspendCatching {
                val response = client.get("$host$path")
                if (!response.status.isSuccess()) {
                    throw SparkExitChainError(
                        "HTTP '${response.status.value}' for '$path'"
                    )
                }
                response.bodyAsText()
            }.onSuccess { return it }.onFailure { lastError = it }
        }
        throw lastError
    }

    private fun status(o: JsonObject) = EsploraTxStatus(
        confirmed = o["confirmed"]?.jsonPrimitive?.booleanOrNull == true,
        blockHeight = (o["block_height"] as? JsonPrimitive)?.longOrNull?.toUInt(),
    )
}

/**
 * Reads node responses: mempool returns the RPC code only (`sendrawtransaction RPC error: {"code":-25}`), esplora the
 * message too, and a package the per-transaction errors.
 */
object SparkExitBroadcastClassifier {
    private val knownMessages = listOf(
        "txn-already-in-mempool",
        "txn-already-known",
        "txn-same-nonwitness-data-in-mempool",
        "already in block chain",
    )
    private val notMatureMessages = listOf("non-BIP68-final", "non-final")
    private val inputsMissingMessages = listOf("bad-txns-inputs-missingorspent", "missing-inputs", "missingorspent")
    private val json = Json { ignoreUnknownKeys = true }

    fun single(status: Int, body: String): SparkExitBroadcastOutcome {
        if (status in 200..299) return SparkExitBroadcastOutcome.Accepted
        return unavailable(status) ?: classify(body)
    }

    /**
     * A package's outcome. An error on the step itself, [parentTxid], decides it. Missing inputs reported on the CPFP
     * child alone (its fee funding or chained change) say nothing about the step, which is then retried.
     */
    fun pkg(status: Int, body: String, parentTxid: String? = null): SparkExitBroadcastOutcome {
        unavailable(status)?.let { return it }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (status !in 200..299 || root == null) return classify(body)
        val message = (root["package_msg"] as? JsonPrimitive)?.contentOrNull
        if (message == "success") return SparkExitBroadcastOutcome.Accepted
        val results = (root["tx-results"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }.orEmpty()
        // The step's own error decides, whatever its child reports and in whatever order the results come.
        val own = results.firstOrNull { (it["txid"] as? JsonPrimitive)?.contentOrNull == parentTxid }
        (own?.get("error") as? JsonPrimitive)?.contentOrNull?.let(::classify)
            ?.takeIf { it != SparkExitBroadcastOutcome.Accepted }
            ?.let { return it }
        val outcomes = results.mapNotNull { result ->
            val error = (result["error"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val txid = (result["txid"] as? JsonPrimitive)?.contentOrNull
            val isChild = parentTxid != null && txid != null && txid != parentTxid
            val outcome = classify(error)
            if (isChild && outcome is SparkExitBroadcastOutcome.InputsMissing) {
                SparkExitBroadcastOutcome.RetryLater(error)
            } else {
                outcome
            }
        }
        if (outcomes.isEmpty()) {
            return if (results.isEmpty()) {
                SparkExitBroadcastOutcome.RetryLater(shortReason(body))
            } else {
                SparkExitBroadcastOutcome.Accepted
            }
        }
        // A child reports missing inputs, or another error, when its parent is the one not yet mature.
        return outcomes.firstOrNull { it is SparkExitBroadcastOutcome.NotMature }
            ?: outcomes.firstOrNull { it is SparkExitBroadcastOutcome.Rejected }
            ?: outcomes.firstOrNull { it != SparkExitBroadcastOutcome.Accepted }
            ?: SparkExitBroadcastOutcome.Accepted
    }

    /** A host that is down, rate limiting or behind a challenge page says nothing about the transaction. */
    private fun unavailable(status: Int): SparkExitBroadcastOutcome? =
        if (status >= 500 || status == 429 || status == 403) {
            SparkExitBroadcastOutcome.RetryLater(
                "HTTP $status"
            )
        } else {
            null
        }

    private val unpaid = Regex("""min relay fee not met,\s*0\s*<""")

    /**
     * A step refused for paying no fee at all, with no child paying for it: the child's chained change is gone, as
     * when an earlier child of the exit lost a race. Read by the driver; a nonzero fee stays a plain rejection.
     */
    fun isUnpaid(outcome: SparkExitBroadcastOutcome): Boolean =
        outcome is SparkExitBroadcastOutcome.Rejected && unpaid.containsMatchIn(outcome.reason)

    private val feeTooLow = Regex("""min relay fee not met,\s*[1-9]|mempool min fee not met""")

    /**
     * A node refused a step whose fee it found too low: a nonzero fee under its relay floor, or under the mempool's
     * floor. A rebuild at the same rate cannot help. An unpaid step and a lost replacement race are not this.
     */
    fun isFeeTooLow(text: String): Boolean = feeTooLow.containsMatchIn(text)

    fun isFeeTooLow(outcome: SparkExitBroadcastOutcome): Boolean =
        outcome is SparkExitBroadcastOutcome.Rejected && isFeeTooLow(outcome.reason)

    private val bareRpcError = Regex("code\\\\?\"\\s*:\\s*-1\\b")

    /**
     * A refusal that names only RPC code -1, with no reason a node gives: Spark's hosted regtest answers every lone
     * `/tx` that way, while the same hex as a package gets a real answer.
     */
    fun isUnplaceable(text: String): Boolean =
        bareRpcError.containsMatchIn(text) && !text.contains("message", ignoreCase = true)

    /**
     * Classifies a node's answer from its whole [text]; the outcome carries only a short reason, the node's message
     * or RPC code, so a host's error page is never kept or shown.
     */
    fun classify(text: String): SparkExitBroadcastOutcome = when {
        knownMessages.any { it in text } || hasRpcCode(-27, text) -> SparkExitBroadcastOutcome.Accepted
        notMatureMessages.any { it in text } -> SparkExitBroadcastOutcome.NotMature(shortReason(text))
        inputsMissingMessages.any { it in text } || hasRpcCode(-25, text) -> SparkExitBroadcastOutcome.InputsMissing(
            shortReason(text)
        )
        else -> SparkExitBroadcastOutcome.Rejected(shortReason(text))
    }

    private val messageField = Regex("""message\\?"\s*:\s*\\?"((?:[^"\\]|\\[^"])*)""")
    private val rpcCodeField = Regex("""code\\?"\s*:\s*(-?\d+)""")
    private val markup = Regex("<[^>]*>")
    private val whitespace = Regex("\\s+")

    /** A node's message, else its RPC code, else the answer without markup, in one line of at most 200 characters. */
    fun shortReason(text: String): String {
        val message = messageField.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val reason = message
            ?: rpcCodeField.find(text)?.groupValues?.get(1)?.let { "RPC error $it" }
            ?: text.replace(markup, " ")
        return reason.replace(whitespace, " ").trim().take(MAX_REASON_LENGTH)
    }

    private const val MAX_REASON_LENGTH = 200

    /** The RPC code, also when the node's error sits JSON-escaped inside mempool's own error object. */
    private fun hasRpcCode(code: Int, text: String) = Regex("""code\\?"\s*:\s*$code\b""").containsMatchIn(text)
}
