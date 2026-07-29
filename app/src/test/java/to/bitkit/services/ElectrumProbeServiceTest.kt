package to.bitkit.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun serverAt(port: Int, protocol: ElectrumProtocol = ElectrumProtocol.TCP) = ElectrumServer(
        host = "127.0.0.1",
        tcp = port,
        ssl = port,
        protocol = protocol,
    )

    /** Answers server.version, then server.features with [genesisHash] when it is not null. */
    private fun startFakeElectrum(genesisHash: String?): Int {
        val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).also { server = it }
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client -> serveElectrum(client, genesisHash) }
            }
        }
        return socket.localPort
    }

    private fun serveElectrum(client: Socket, genesisHash: String?) {
        val reader = client.getInputStream().bufferedReader()
        val writer = client.getOutputStream().bufferedWriter()

        readAndRespond(reader, writer) { """{"id":0,"result":["fake-electrs","1.4"]}""" }
        readAndRespond(reader, writer) {
            if (genesisHash != null) {
                """{"id":1,"result":{"genesis_hash":"$genesisHash"}}"""
            } else {
                """{"id":1,"error":{"code":-32601,"message":"unknown method"}}"""
            }
        }
    }

    private fun readAndRespond(reader: BufferedReader, writer: java.io.Writer, response: () -> String) {
        reader.readLine() ?: return
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
