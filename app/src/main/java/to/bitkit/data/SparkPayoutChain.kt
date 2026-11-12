package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.lightningdevkit.ldknode.Network
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a Spark withdrawal's payout transaction is mined, asked of the Esplora chain Spark reads. Unlike the payout
 * address's unspent outputs, the transaction's status still answers once the contact has spent the payout.
 */
@Singleton
class SparkPayoutChain @Inject constructor(
    private val client: HttpClient,
) {
    companion object {
        private const val TAG = "SparkPayoutChain"
        private const val SPARK_REGTEST_ESPLORA_URL = "https://regtest-mempool.us-west-2.sparkinfra.net/api"
        private val json = Json { ignoreUnknownKeys = true }

        /** The Esplora to ask on [network]: the one Spark reads there, or null where the app knows none. */
        fun esploraUrl(network: Network): String? = when (network) {
            Network.BITCOIN -> Env.bitkitEsploraUrl
            Network.REGTEST -> SPARK_REGTEST_ESPLORA_URL
            else -> null
        }
    }

    /** Whether [txid] is in a block; null when this network has no Esplora to ask or the lookup failed. */
    suspend fun isMined(txid: String): Boolean? {
        val baseUrl = esploraUrl(Env.network) ?: return null
        return runSuspendCatching {
            val response = client.get("$baseUrl/tx/$txid/status")
            // Spark can list a payout before it is broadcast, and the chain then knows no such transaction yet.
            if (response.status == HttpStatusCode.NotFound) return@runSuspendCatching false
            check(response.status.isSuccess()) { "status ${response.status.value}" }
            json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("confirmed").jsonPrimitive.boolean
        }.getOrElse {
            Logger.warn("Failed to look up Spark payout transaction '$txid'", it, context = TAG)
            null
        }
    }
}
