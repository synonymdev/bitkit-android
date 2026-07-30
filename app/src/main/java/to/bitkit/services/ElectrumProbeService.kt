package to.bitkit.services

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.lightningdevkit.ldknode.Network
import to.bitkit.di.IoDispatcher
import to.bitkit.env.Env
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.ElectrumProtocol
import to.bitkit.models.ElectrumServer
import to.bitkit.utils.AppError
import to.bitkit.utils.Logger
import java.io.BufferedReader
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.time.Duration.Companion.seconds

/**
 * Probes an electrum server over its own socket before the node is asked to use it.
 *
 * A failed `node.start()` leaves the node's electrum background tasks wedged, so `free_node` then
 * blocks for tens of seconds instead of milliseconds. Rejecting a misconfigured server here means
 * the node is never torn down and rebuilt for one, so that path stops producing wedged releases.
 */
@Singleton
class ElectrumProbeService @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {
    companion object {
        private const val TAG = "ElectrumProbeService"

        /** Budget for the TCP connect and, on SSL, the TLS handshake. */
        private val CONNECT_TIMEOUT = 5.seconds

        /** Budget for each JSON-RPC response line. */
        private val RESPONSE_TIMEOUT = 5.seconds

        private const val CLIENT_NAME = "bitkit"
        private const val PROTOCOL_VERSION = "1.4"

        /** JSON-RPC id of the `server.version` request, echoed back by a well-behaved server. */
        private const val VERSION_REQUEST_ID = 0

        /** JSON-RPC id of the `server.features` request. */
        private const val FEATURES_REQUEST_ID = 1
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probe(
        server: ElectrumServer,
        network: Network = Env.network,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            openSocket(server).use { socket ->
                socket.soTimeout = RESPONSE_TIMEOUT.inWholeMilliseconds.toInt()
                val reader = socket.getInputStream().bufferedReader()
                val writer = socket.getOutputStream().bufferedWriter()

                // A host that accepts the connection but does not speak electrum drops or resets it
                // mid-exchange, so report that as a probe verdict rather than a raw socket error.
                runCatching {
                    // Version negotiation has to succeed before server.features may be treated as
                    // optional, otherwise a server erroring on both would probe clean.
                    request(reader, writer, VERSION_REQUEST_ID, "server.version", versionParams()).getOrThrow()

                    val features = request(reader, writer, FEATURES_REQUEST_ID, "server.features", "[]")
                    verifyNetwork(features.getOrNull(), server, network)
                }.getOrElse {
                    throw it as? ElectrumProbeError ?: ElectrumProbeError.NotElectrum(server, it)
                }
            }
            Logger.info("Probed electrum server '$server' successfully", context = TAG)
        }
    }

    private fun openSocket(server: ElectrumServer): Socket {
        val plain = Socket()
        runCatching {
            plain.connect(InetSocketAddress(server.host, server.getPort()), CONNECT_TIMEOUT.inWholeMilliseconds.toInt())
        }.onFailure {
            plain.runCatching { close() }
            throw ElectrumProbeError.Unreachable(server, it)
        }

        if (server.protocol == ElectrumProtocol.TCP) return plain

        // A TLS handshake against a plain-TCP server hangs without a read timeout, which is the
        // misconfiguration that wedges the node's release when it is left to node.start().
        return runCatching {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = factory.createSocket(plain, server.host, server.getPort(), true) as SSLSocket
            ssl.soTimeout = CONNECT_TIMEOUT.inWholeMilliseconds.toInt()
            ssl.startHandshake()
            ssl
        }.getOrElse {
            plain.runCatching { close() }
            throw ElectrumProbeError.ProtocolMismatch(server, it)
        }
    }

    private fun request(
        reader: BufferedReader,
        writer: Writer,
        id: Int,
        method: String,
        params: String,
    ): Result<RpcResponse> = runCatching {
        writer.write("""{"id":$id,"jsonrpc":"2.0","method":"$method","params":$params}""" + "\n")
        writer.flush()

        val line = reader.readLine() ?: throw AppError("Closed connection before answering '$method'")
        val response = json.decodeFromString<RpcResponse>(line)

        when {
            response.id != id ->
                throw AppError("Answered '$method' with id '${response.id}', expected '$id'")

            !response.error.isNullOrJsonNull() ->
                throw AppError("Answered '$method' with error '${response.error}'")

            response.result.isNullOrJsonNull() ->
                throw AppError("Answered '$method' without a result")

            else -> response
        }
    }

    private fun verifyNetwork(features: RpcResponse?, server: ElectrumServer, network: Network) {
        val genesis = (features?.result as? JsonObject)?.get("genesis_hash")?.jsonPrimitive?.contentOrNull
        if (genesis == null) {
            // server.features is optional, so a server that answered version negotiation but cannot
            // report a genesis hash stays usable; only the network check is skipped.
            Logger.warn("Skipped network check, server '$server' reported no genesis hash", context = TAG)
            return
        }

        val expected = genesisHashOf(network)
        if (!genesis.equals(expected, ignoreCase = true)) {
            throw ElectrumProbeError.NetworkMismatch(server, expected = expected, actual = genesis)
        }
    }

    private fun versionParams() = """["$CLIENT_NAME","$PROTOCOL_VERSION"]"""

    private fun genesisHashOf(network: Network): String = when (network) {
        Network.BITCOIN -> "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
        Network.TESTNET -> "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
        Network.SIGNET -> "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
        Network.REGTEST -> "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"
    }
}

/** A JSON-RPC reply, kept only as far as the probe needs to trust it. */
@Serializable
private data class RpcResponse(
    val id: Int? = null,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
)

private fun JsonElement?.isNullOrJsonNull() = this == null || this is JsonNull

sealed class ElectrumProbeError(message: String, cause: Throwable? = null) : AppError(message, cause) {
    class Unreachable(server: ElectrumServer, cause: Throwable) :
        ElectrumProbeError("Could not reach electrum server '$server'", cause)

    class ProtocolMismatch(server: ElectrumServer, cause: Throwable) :
        ElectrumProbeError("Failed TLS handshake with electrum server '$server', check the protocol", cause)

    class NotElectrum(server: ElectrumServer, cause: Throwable? = null) :
        ElectrumProbeError("Received no electrum response from '$server'", cause)

    class NetworkMismatch(server: ElectrumServer, expected: String, actual: String) :
        ElectrumProbeError("Rejected electrum server '$server' on wrong network, expected '$expected' got '$actual'")
}
