package to.bitkit.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import to.bitkit._LDK
import to.bitkit.ext.toByteArray
import to.bitkit.ext.toHex
import to.bitkit.ldk.Ldk
import to.bitkit.ldk.ldkDir
import to.bitkit.ui.HOST
import to.bitkit.ui.PEER
import to.bitkit.ui.PORT
import to.bitkit.ui.REST
import java.io.File
import java.io.FileWriter
import java.net.InetSocketAddress
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
    suspend fun connectPeer(
        pubKeyHex: String = PEER,
        hostname: String = HOST,
        port: Int = PORT.toInt(),
    ): Boolean

    suspend fun disconnectPeer(pubKeyHex: String): Boolean
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

    override suspend fun connectPeer(
        pubKeyHex: String,
        hostname: String,
        port: Int,
    ): Boolean {
        Log.d(_LDK, "Connecting peer: $pubKeyHex")
        try {
            val nioPeerHandler = Ldk.channelManagerConstructor.nio_peer_handler!!
            nioPeerHandler.connect(
                pubKeyHex.toByteArray(),
                InetSocketAddress(hostname, port), 5555
            )
            Log.d(_LDK, "Connected peer: $pubKeyHex")

            val file = File("$ldkDir/peers.txt")

            if (!file.exists()) {
                file.createNewFile()
            }

            // Open a FileWriter to write to the file (append mode)
            val fileWriter = FileWriter(file, true)

            // Write the IP address to the file
            fileWriter.write("$pubKeyHex@$hostname:$port")
            fileWriter.write(System.lineSeparator()) // Add a newline for readability

            // Close the FileWriter
            fileWriter.close()
            return true
        } catch (e: Exception) {
            Log.d(_LDK, "Failed to connect peer:\n" + e.message)
            return false
        }
    }

    override suspend fun disconnectPeer(pubKeyHex: String): Boolean {
        try {
            val nioPeerHandler = Ldk.channelManagerConstructor.nio_peer_handler!!
            nioPeerHandler.disconnect(pubKeyHex.toByteArray())
            return true
        } catch (e: Exception) {
            Log.d(_LDK, "Failed to disconnect peer:\n" + e.message)
            return false
        }
    }
}