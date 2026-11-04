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
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.AppError

data class EsploraTxStatus(val confirmed: Boolean, val blockHeight: UInt? = null)

data class EsploraUtxo(val txid: String, val vout: UInt, val value: ULong, val status: EsploraTxStatus)

/** What a broadcast came to. Accepted includes a transaction the node already had. */
sealed interface SparkExitBroadcastOutcome {
    val summary: String

    data object Accepted : SparkExitBroadcastOutcome {
        override val summary = "accepted"
    }

    /** Not final yet (timelock), or its parent is not visible yet: the next tick tries again. */
    data class RetryLater(val reason: String) : SparkExitBroadcastOutcome {
        override val summary get() = "retry: $reason"
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
    suspend fun broadcast(tx: RecoveryTransaction): SparkExitBroadcastOutcome
}

class SparkExitChainError(message: String) : AppError(message)

/**
 * esplora and mempool.space REST, on the hosts the Spark SDK uses by default: a transaction with a CPFP child goes
 * out as a package through `/v1/txs/package` (a JSON array of hex), one without as a single `/tx`.
 */
class EsploraSparkExitChain(
    private val hosts: List<String>,
    private val client: HttpClient,
) : SparkExitChain {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The Spark SDK's default chain service per network (breez spark-sdk `default_rest_chain_services`). */
        fun defaultHosts(sparkNetwork: String?): List<String> = when (sparkNetwork) {
            "mainnet" -> listOf("https://mempool.space/api", "https://blockstream.info/api")
            "regtest" -> listOf("https://regtest-mempool.us-west-2.sparkinfra.net/api")
            else -> emptyList()
        }

        private val NOT_HERE = setOf(404, 405)
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

    /** Tries each host until one accepts. Otherwise a node's refusal wins over a host that was only unavailable. */
    override suspend fun broadcast(tx: RecoveryTransaction): SparkExitBroadcastOutcome {
        val outcomes = mutableListOf<SparkExitBroadcastOutcome>()
        for (host in hosts) {
            val child = tx.cpfpTxHex
            val attempt = if (child != null) {
                submitPackage(
                    host,
                    listOf(tx.txHex, child)
                )
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

    private suspend fun submitPackage(host: String, hexes: List<String>): SparkExitBroadcastOutcome? {
        val body = JsonArray(hexes.map(::JsonPrimitive)).toString()
        for (path in listOf("/v1/txs/package", "/txs/package")) {
            val response = post("$host$path", TextContent(body, ContentType.Application.Json)) ?: return null
            if (response.status.value in NOT_HERE) continue
            return SparkExitBroadcastClassifier.pkg(response.status.value, response.bodyAsText())
        }
        return null
    }

    private suspend fun submitSingle(host: String, hex: String): SparkExitBroadcastOutcome? {
        val response = post("$host/tx", TextContent(hex, ContentType.Text.Plain)) ?: return null
        if (response.status.value in NOT_HERE) return null
        return SparkExitBroadcastClassifier.single(response.status.value, response.bodyAsText())
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
        for (host in hosts) {
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
    private val waitMessages = listOf(
        "non-BIP68-final",
        "non-final",
        "bad-txns-inputs-missingorspent",
        "missing-inputs",
        "missingorspent",
    )
    private val json = Json { ignoreUnknownKeys = true }

    fun single(status: Int, body: String): SparkExitBroadcastOutcome {
        if (status in 200..299) return SparkExitBroadcastOutcome.Accepted
        return unavailable(status) ?: classify(body)
    }

    fun pkg(status: Int, body: String): SparkExitBroadcastOutcome {
        unavailable(status)?.let { return it }
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
        if (status !in 200..299 || root == null) return classify(body)
        val message = (root["package_msg"] as? JsonPrimitive)?.contentOrNull
        if (message == "success") return SparkExitBroadcastOutcome.Accepted
        val results = (root["tx-results"] as? JsonObject)?.values?.mapNotNull { it as? JsonObject }.orEmpty()
        val errors = results.mapNotNull { (it["error"] as? JsonPrimitive)?.contentOrNull }
        if (errors.isEmpty()) {
            return if (results.isEmpty()) {
                SparkExitBroadcastOutcome.RetryLater(
                    body
                )
            } else {
                SparkExitBroadcastOutcome.Accepted
            }
        }
        val outcomes = errors.map(::classify)
        return outcomes.firstOrNull { it is SparkExitBroadcastOutcome.Rejected }
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

    fun classify(text: String): SparkExitBroadcastOutcome = when {
        knownMessages.any { it in text } || hasRpcCode(-27, text) -> SparkExitBroadcastOutcome.Accepted
        waitMessages.any { it in text } || hasRpcCode(-25, text) -> SparkExitBroadcastOutcome.RetryLater(text)
        else -> SparkExitBroadcastOutcome.Rejected(text)
    }

    /** The RPC code, also when the node's error sits JSON-escaped inside mempool's own error object. */
    private fun hasRpcCode(code: Int, text: String) = Regex("""code\\?"\s*:\s*$code\b""").containsMatchIn(text)
}
