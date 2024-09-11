package to.bitkit.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import to.bitkit.env.REST
import to.bitkit.ext.toHex
import javax.inject.Inject

interface RestApi {
    suspend fun getLatestBlockHash(): String
    suspend fun getLatestBlockHeight(): Int
    suspend fun broadcastTx(tx: ByteArray): String
    suspend fun getTx(txid: String): Tx
    suspend fun getTxHex(txid: String): String
    suspend fun getHeader(hash: String): String
    suspend fun getMerkleProof(txid: String): MerkleProof
    suspend fun getOutputSpent(txid: String, outputIndex: Int): OutputSpent
}

class EsploraApi @Inject constructor(
    private val client: HttpClient,
) : RestApi {
    override suspend fun getLatestBlockHash(): String {
        val httpResponse: HttpResponse = client.get("$REST/blocks/tip/hash")
        return httpResponse.body()
    }

    override suspend fun getLatestBlockHeight(): Int {
        val httpResponse: HttpResponse = client.get("$REST/blocks/tip/height")
        return httpResponse.body<Int>().toInt()
    }

    override suspend fun broadcastTx(tx: ByteArray): String {
        val response: HttpResponse = client.post("$REST/tx") {
            setBody(tx.toHex())
        }

        return response.body()
    }

    override suspend fun getTx(txid: String): Tx {
        return client.get("$REST/tx/${txid}").body()
    }

    override suspend fun getTxHex(txid: String): String {
        return client.get("$REST/tx/${txid}/hex").body()
    }

    override suspend fun getHeader(hash: String): String {
        return client.get("$REST/block/${hash}/header").body()
    }

    override suspend fun getMerkleProof(txid: String): MerkleProof {
        return client.get("$REST/tx/${txid}/merkle-proof").body()
    }

    override suspend fun getOutputSpent(txid: String, outputIndex: Int): OutputSpent {
        return client.get("$REST/tx/${txid}/outspend/${outputIndex}").body()
    }
}

@Serializable
data class Tx(
    val txid: String,
    val status: TxStatus,
)

@Serializable
data class TxStatus(
    @SerialName("confirmed")
    val isConfirmed: Boolean,
    @SerialName("block_height")
    val blockHeight: Int? = null,
    @SerialName("block_hash")
    val blockHash: String? = null,
)

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
