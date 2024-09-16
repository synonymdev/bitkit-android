package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.bitkit.env.Env
import to.bitkit.ext.hex
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EsploraApi @Inject constructor(
    private val client: HttpClient,
) {
    private val baseUrl = Env.esploraUrl

    suspend fun getLatestBlockHash(): String {
        val httpResponse: HttpResponse = client.get("$baseUrl/blocks/tip/hash")
        return httpResponse.body()
    }

    suspend fun getLatestBlockHeight(): Int {
        val httpResponse: HttpResponse = client.get("$baseUrl/blocks/tip/height")
        return httpResponse.body<Int>().toInt()
    }

    suspend fun broadcastTx(tx: ByteArray): String {
        val response: HttpResponse = client.post("$baseUrl/tx") {
            setBody(tx.hex)
        }

        return response.body()
    }

    suspend fun getTx(txid: String): Tx {
        return client.get("$baseUrl/tx/${txid}").body()
    }

    suspend fun getTxHex(txid: String): String {
        return client.get("$baseUrl/tx/${txid}/hex").body()
    }

    suspend fun getHeader(hash: String): String {
        return client.get("$baseUrl/block/${hash}/header").body()
    }

    suspend fun getMerkleProof(txid: String): MerkleProof {
        return client.get("$baseUrl/tx/${txid}/merkle-proof").body()
    }

    suspend fun getOutputSpent(txid: String, outputIndex: Int): OutputSpent {
        return client.get("$baseUrl/tx/${txid}/outspend/${outputIndex}").body()
    }
}

@Serializable
data class Tx(
    val txid: String,
    val status: TxStatus,
) {
    @Serializable
    data class TxStatus(
        @SerialName("confirmed")
        val isConfirmed: Boolean,
        @SerialName("block_height")
        val blockHeight: Int? = null,
        @SerialName("block_hash")
        val blockHash: String? = null,
    )
}

@Serializable
data class OutputSpent(
    val spent: Boolean,
)

@Serializable
data class MerkleProof(
    @SerialName("block_height")
    val blockHeight: Int,
    @Suppress("ArrayInDataClass")
    val merkle: Array<String>,
    val pos: Int,
)
