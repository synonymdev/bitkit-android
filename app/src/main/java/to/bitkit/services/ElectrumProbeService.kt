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
import java.io.ByteArrayOutputStream
import java.io.InputStream
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

        /** Maximum JSON-RPC response line accepted from a probed Electrum endpoint. */
        internal const val MAX_RESPONSE_LINE_BYTES = 16 * 1024

        private const val CLIENT_NAME = "bitkit"
        private const val PROTOCOL_VERSION = "1.4"

        /** JSON-RPC id of the `server.version` request, echoed back by a well-behaved server. */
        private const val VERSION_REQUEST_ID = 0

        /** JSON-RPC id of the `server.features` request. */
        private const val FEATURES_REQUEST_ID = 1
    }

    // Deliberately not the injected Json: that one sets prettyPrint, and electrum is line-delimited,
    // so a multi-line request would be read as a truncated line. encodeDefaults keeps `jsonrpc` and
    // an empty `params` on the wire, which servers expect.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun probe(
        server: ElectrumServer,
        network: Network = Env.network,
    ): Result<Unit> = withContext(ioDispatcher) {
        runSuspendCatching {
            openSocket(server).use { socket ->
                socket.soTimeout = RESPONSE_TIMEOUT.inWholeMilliseconds.toInt()
                val input = socket.getInputStream()
                val writer = socket.getOutputStream().bufferedWriter()

                // A host that accepts the connection but does not speak electrum drops or resets it
                // mid-exchange, so report that as a probe verdict rather than a raw socket error.
                runCatching {
                    // Version negotiation has to succeed before server.features may be treated as
                    // optional, otherwise a server erroring on both would probe clean.
                    request(input, writer, VERSION_REQUEST_ID, "server.version", versionParams()).getOrThrow()

                    val features = request(input, writer, FEATURES_REQUEST_ID, "server.features")
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
        input: InputStream,
        writer: Writer,
        id: Int,
        method: String,
        params: List<String> = emptyList(),
    ): Result<RpcResponse> = runCatching {
        writer.appendLine(json.encodeToString(RpcRequest(id = id, method = method, params = params)))
        writer.flush()

        val line = readLineBounded(input, method)
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

    private fun readLineBounded(input: InputStream, method: String): String {
        val buffer = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            when {
                b == -1 -> throw AppError(
                    if (buffer.size() == 0) {
                        "Closed connection before answering '$method'"
                    } else {
                        "Closed connection before a complete response to '$method'"
                    },
                )
                b == '\n'.code -> {
                    val raw = buffer.toByteArray()
                    val end = if (raw.isNotEmpty() && raw.last() == '\r'.code.toByte()) raw.size - 1 else raw.size
                    return raw.decodeToString(endIndex = end)
                }
                buffer.size() >= MAX_RESPONSE_LINE_BYTES ->
                    throw AppError("Response to '$method' exceeded '$MAX_RESPONSE_LINE_BYTES' bytes")
                else -> buffer.write(b)
            }
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

    private fun versionParams() = listOf(CLIENT_NAME, PROTOCOL_VERSION)

    private fun genesisHashOf(network: Network): String = when (network) {
        Network.BITCOIN -> "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"
        Network.TESTNET -> "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943"
        Network.SIGNET -> "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6"
        Network.REGTEST -> "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"
    }
}

/** A JSON-RPC call, matching the envelope the probe expects back. */
@Serializable
private data class RpcRequest(
    val id: Int,
    val jsonrpc: String = "2.0",
    val method: String,
    val params: List<String> = emptyList(),
)

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
