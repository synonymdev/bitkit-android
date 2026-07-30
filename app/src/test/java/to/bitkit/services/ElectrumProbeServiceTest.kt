package to.bitkit.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.lightningdevkit.ldknode.Network
import to.bitkit.models.ElectrumProtocol
import to.bitkit.models.ElectrumServer
import to.bitkit.test.BaseUnitTest
import java.io.BufferedReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val REGTEST_GENESIS = "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"
private const val MAINNET_GENESIS = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumProbeServiceTest : BaseUnitTest() {
    private val sut = ElectrumProbeService(ioDispatcher = Dispatchers.IO)

    private var server: ServerSocket? = null

    @After
    fun tearDown() {
        server?.runCatching { close() }
        server = null
    }

    @Test
    fun `probe succeeds against an electrum server on the expected network`() = test {
        val port = startFakeElectrum(genesisHash = REGTEST_GENESIS)

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `probe rejects a server on a different network`() = test {
        val port = startFakeElectrum(genesisHash = MAINNET_GENESIS)

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.NetworkMismatch>(result.exceptionOrNull())
    }

    @Test
    fun `probe accepts a server that does not report a genesis hash`() = test {
        val port = startFakeElectrum(genesisHash = null)

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertTrue(result.isSuccess) // server.features is optional, so this must not reject
    }

    // Regression: the version reply must be validated as a JSON-RPC envelope, not merely parsed.
    // A server that errors on version negotiation is one the real LDK client rejects at startup,
    // and letting it through here recreates the failed-start wedge the probe exists to prevent.
    @Test
    fun `probe rejects a server that errors on version negotiation`() = test {
        val port = startFakeElectrum(versionReply = """{"id":0,"error":{"code":1,"message":"unsupported"}}""")

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.NotElectrum>(result.exceptionOrNull())
    }

    @Test
    fun `probe rejects a version reply with no result`() = test {
        val port = startFakeElectrum(versionReply = """{"id":0,"jsonrpc":"2.0"}""")

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.NotElectrum>(result.exceptionOrNull())
    }

    @Test
    fun `probe rejects a version reply answering a different id`() = test {
        val port = startFakeElectrum(versionReply = """{"id":99,"result":["fake-electrs","1.4"]}""")

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.NotElectrum>(result.exceptionOrNull())
    }

    // The rejection reason has to survive as the cause, otherwise the log says only "no electrum
    // response" and a wrong protocol version looks the same as a dropped connection.
    @Test
    fun `probe keeps why the version reply was rejected as the cause`() = test {
        val port = startFakeElectrum(versionReply = """{"id":0,"error":{"code":1,"message":"unsupported"}}""")

        val error = sut.probe(serverAt(port), network = Network.REGTEST).exceptionOrNull()

        assertIs<ElectrumProbeError.NotElectrum>(error)
        val cause = error.cause?.message.orEmpty()
        assertTrue("server.version" in cause, "cause should name the request, was '$cause'")
        assertTrue("error" in cause, "cause should report the rejection reason, was '$cause'")
    }

    // Regression: a features reply that fails validation must not be read as "no genesis hash" on a
    // server whose version negotiation never succeeded — that combination used to probe clean.
    @Test
    fun `probe rejects a server that errors on both version and features`() = test {
        val port = startFakeElectrum(
            versionReply = """{"id":0,"error":{"code":1,"message":"unsupported"}}""",
            featuresReply = """{"id":1,"error":{"code":-32601,"message":"unknown method"}}""",
        )

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.NotElectrum>(result.exceptionOrNull())
    }

    @Test
    fun `probe rejects a host that never answers the electrum handshake`() = test {
        val port = startSilentServer()

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.NotElectrum>(result.exceptionOrNull())
    }

    @Test
    fun `probe rejects an unreachable host`() = test {
        val port = ServerSocket(0).use { it.localPort } // closed immediately, nothing listens

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.Unreachable>(result.exceptionOrNull())
    }

    // The @settings_10 wedge condition: TLS pointed at a plain-TCP electrum server. Left to
    // node.start() this hangs and wedges the node's release; the probe must refuse it instead.
    @Test
    fun `probe rejects TLS against a plain tcp server`() = test {
        val port = startFakeElectrum(genesisHash = REGTEST_GENESIS)

        val result = sut.probe(serverAt(port, ElectrumProtocol.SSL), network = Network.REGTEST)

        assertIs<ElectrumProbeError.ProtocolMismatch>(result.exceptionOrNull())
    }

    @Test
    fun `probe reports the requested server in its error`() = test {
        val port = startSilentServer()

        val error = sut.probe(serverAt(port), network = Network.REGTEST).exceptionOrNull()

        assertEquals(true, error?.message?.contains("$port"))
    }

    // The requests are serialized rather than hand-built, so pin the envelope actually put on the
    // wire: a real electrum server has to accept it, and no fake-server assertion covers that.
    @Test
    fun `probe sends well formed json rpc requests`() = test {
        val port = startFakeElectrum(genesisHash = REGTEST_GENESIS)

        sut.probe(serverAt(port), network = Network.REGTEST)

        val sent = synchronized(received) { received.toList() }
        assertEquals(2, sent.size)

        val version = Json.parseToJsonElement(sent[0]).jsonObject
        assertEquals(0, version.getValue("id").jsonPrimitive.int)
        assertEquals("2.0", version.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals("server.version", version.getValue("method").jsonPrimitive.content)
        assertEquals(
            listOf("bitkit", "1.4"),
            version.getValue("params").jsonArray.map { it.jsonPrimitive.content },
        )

        val features = Json.parseToJsonElement(sent[1]).jsonObject
        assertEquals(1, features.getValue("id").jsonPrimitive.int)
        assertEquals("server.features", features.getValue("method").jsonPrimitive.content)
        assertTrue(features.getValue("params").jsonArray.isEmpty())
    }

    private fun serverAt(port: Int, protocol: ElectrumProtocol = ElectrumProtocol.TCP) = ElectrumServer(
        host = "127.0.0.1",
        tcp = port,
        ssl = port,
        protocol = protocol,
    )

    /**
     * Answers server.version then server.features. Defaults are a well-formed pair; either reply can
     * be overridden to exercise a malformed envelope.
     */
    private fun startFakeElectrum(
        genesisHash: String? = null,
        versionReply: String = """{"id":0,"jsonrpc":"2.0","result":["fake-electrs","1.4"]}""",
        featuresReply: String = genesisHash
            ?.let { """{"id":1,"jsonrpc":"2.0","result":{"genesis_hash":"$it"}}""" }
            ?: """{"id":1,"error":{"code":-32601,"message":"unknown method"}}""",
    ): Int {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { server = it }
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client -> serveElectrum(client, versionReply, featuresReply) }
            }
        }
        return socket.localPort
    }

    private fun serveElectrum(client: Socket, versionReply: String, featuresReply: String) {
        val reader = client.getInputStream().bufferedReader()
        val writer = client.getOutputStream().bufferedWriter()

        readAndRespond(reader, writer) { versionReply }
        readAndRespond(reader, writer) { featuresReply }
    }

    /** Requests the fake server received, in order, so the encoded envelope can be asserted. */
    private val received = mutableListOf<String>()

    private fun readAndRespond(reader: BufferedReader, writer: java.io.Writer, response: () -> String) {
        val request = reader.readLine() ?: return
        synchronized(received) { received += request }
        writer.write(response() + "\n")
        writer.flush()
    }

    /** Accepts the connection but never speaks electrum, like a non-electrum service on the port. */
    private fun startSilentServer(): Int {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { server = it }
        thread(isDaemon = true) {
            runCatching { socket.accept().use { it.getInputStream().read() } }
        }
        return socket.localPort
    }
}
