package to.bitkit.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.x509.X509V3CertificateGenerator
import org.junit.After
import org.junit.Test
import org.lightningdevkit.ldknode.Network
import to.bitkit.ext.nowMillis
import to.bitkit.models.ElectrumProtocol
import to.bitkit.models.ElectrumServer
import to.bitkit.test.BaseUnitTest
import to.bitkit.utils.AppError
import java.io.BufferedReader
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertPathValidatorException
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

private const val REGTEST_GENESIS = "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206"
private const val MAINNET_GENESIS = "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f"

private const val VERSION_REPLY = """{"id":0,"jsonrpc":"2.0","result":["fake-electrs","1.4"]}"""

private fun featuresReplyWith(genesisHash: String) =
    """{"id":1,"jsonrpc":"2.0","result":{"genesis_hash":"$genesisHash"}}"""

private const val LOOPBACK = "127.0.0.1"
private const val RSA_KEY_SIZE = 2048
private val CERTIFICATE_VALIDITY = 1.days
private val KEY_PASSWORD = "probe".toCharArray()

@OptIn(ExperimentalCoroutinesApi::class)
class ElectrumProbeServiceTest : BaseUnitTest() {
    private val sut = ElectrumProbeService(ioDispatcher = Dispatchers.IO)

    private var server: ServerSocket? = null

    private var defaultSslContext: SSLContext? = null

    @After
    fun tearDown() {
        server?.runCatching { close() }
        server = null
        defaultSslContext?.let { SSLContext.setDefault(it) }
        defaultSslContext = null
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
    fun `probe rejects a server that streams an oversized response line`() = test {
        val port = startOversizedLineServer()

        val result = sut.probe(serverAt(port), network = Network.REGTEST)

        assertIs<ElectrumProbeError.NotElectrum>(result.exceptionOrNull())
    }

    @Test
    fun `probe keeps the oversized-line cap in the rejection cause`() = test {
        val port = startOversizedLineServer()

        val error = sut.probe(serverAt(port), network = Network.REGTEST).exceptionOrNull()

        assertIs<ElectrumProbeError.NotElectrum>(error)
        val cause = error.cause?.message.orEmpty()
        assertTrue("server.version" in cause, "cause should name the request, was '$cause'")
        assertTrue(
            "${ElectrumProbeService.MAX_RESPONSE_LINE_BYTES}" in cause,
            "cause should report the line cap, was '$cause'",
        )
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

    // Regression: TLS to a host named by an IP or an alias, answered by a server whose certificate
    // chain is valid but was issued for another name. A raw SSLSocket checks the chain and not the
    // name, so this probed clean and only the node's own electrum client rejected it — after the
    // node restart the probe exists to avoid.
    @Test
    fun `probe rejects a certificate issued for another host`() = test {
        val port = startTlsElectrum(certificateFor = GeneralName(GeneralName.dNSName, "wrong.example"))

        val result = sut.probe(serverAt(port, ElectrumProtocol.SSL), network = Network.REGTEST)

        assertIs<ElectrumProbeError.UntrustedCertificate>(result.exceptionOrNull())
    }

    // The other half of the pair: verifying the name must not reject a certificate that does name
    // the host, otherwise the probe would refuse servers the node itself accepts.
    @Test
    fun `probe accepts a certificate issued for the host it connected to`() = test {
        val port = startTlsElectrum(certificateFor = GeneralName(GeneralName.iPAddress, LOOPBACK))

        val result = sut.probe(serverAt(port, ElectrumProtocol.SSL), network = Network.REGTEST)

        assertTrue(result.isSuccess)
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

    // A self-signed Fulcrum or electrs on its SSL port fails the handshake with the protocol and the
    // port already correct, so it must not be reported as a protocol mismatch.
    @Test
    fun `toTlsProbeError reports an untrusted certificate chain`() {
        val handshake = SSLHandshakeException("PKIX path building failed").apply {
            initCause(AppError("validator failed", CertPathValidatorException("no trusted path")))
        }

        val error = handshake.toTlsProbeError(serverAt(50002, ElectrumProtocol.SSL))

        assertIs<ElectrumProbeError.UntrustedCertificate>(error)
    }

    @Test
    fun `toTlsProbeError reports an unverified peer`() {
        val error = SSLPeerUnverifiedException("hostname mismatch")
            .toTlsProbeError(serverAt(50002, ElectrumProtocol.SSL))

        assertIs<ElectrumProbeError.UntrustedCertificate>(error)
    }

    @Test
    fun `toTlsProbeError reports a handshake timeout as a protocol mismatch`() {
        val error = SocketTimeoutException("Read timed out")
            .toTlsProbeError(serverAt(50002, ElectrumProtocol.SSL))

        assertIs<ElectrumProbeError.ProtocolMismatch>(error)
    }

    @Test
    fun `toTlsProbeError reports a non tls reply as a protocol mismatch`() {
        val error = SSLException("Unsupported or unrecognized SSL message")
            .toTlsProbeError(serverAt(50002, ElectrumProtocol.SSL))

        assertIs<ElectrumProbeError.ProtocolMismatch>(error)
    }

    private fun serverAt(port: Int, protocol: ElectrumProtocol = ElectrumProtocol.TCP) = ElectrumServer(
        host = LOOPBACK,
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
        versionReply: String = VERSION_REPLY,
        featuresReply: String = genesisHash
            ?.let { featuresReplyWith(it) }
            ?: """{"id":1,"error":{"code":-32601,"message":"unknown method"}}""",
    ): Int {
        val socket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).also { server = it }
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

    private fun startOversizedLineServer(): Int {
        val socket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).also { server = it }
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    val reader = client.getInputStream().bufferedReader()
                    reader.readLine() ?: return@use
                    val payload = ByteArray(ElectrumProbeService.MAX_RESPONSE_LINE_BYTES + 1) { 'x'.code.toByte() }
                    client.getOutputStream().write(payload)
                    client.getOutputStream().flush()
                    client.getInputStream().read()
                }
            }
        }
        return socket.localPort
    }

    /**
     * Serves electrum over TLS behind a self-signed certificate naming [certificateFor], made the
     * only certificate the JVM trusts. The chain is therefore valid and only the name can fail, so
     * what the probe reports is decided by whether it asks JSSE to check the name at all.
     */
    private fun startTlsElectrum(certificateFor: GeneralName, genesisHash: String = REGTEST_GENESIS): Int {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_SIZE) }.generateKeyPair()
        val certificate = selfSignedCertificate(keyPair, certificateFor)
        trustOnly(certificate)

        val socket = tlsServerFactory(keyPair, certificate)
            .createServerSocket(0, 1, InetAddress.getByName(LOOPBACK))
            .also { server = it }
        thread(isDaemon = true) {
            runCatching {
                socket.accept().use { client ->
                    serveElectrum(client, VERSION_REPLY, featuresReplyWith(genesisHash))
                }
            }
        }
        return socket.localPort
    }

    @Suppress("DEPRECATION") // bcprov carries no other certificate generator, and bcpkix is not a dependency
    private fun selfSignedCertificate(keyPair: KeyPair, name: GeneralName): X509Certificate {
        val generated = X509V3CertificateGenerator().apply {
            setSerialNumber(BigInteger.ONE)
            setIssuerDN(X500Principal("CN=electrum-probe-test"))
            setSubjectDN(X500Principal("CN=electrum-probe-test"))
            setNotBefore(Date(nowMillis() - CERTIFICATE_VALIDITY.inWholeMilliseconds))
            setNotAfter(Date(nowMillis() + CERTIFICATE_VALIDITY.inWholeMilliseconds))
            setPublicKey(keyPair.public)
            setSignatureAlgorithm("SHA256withRSA")
            addExtension(Extension.subjectAlternativeName, false, GeneralNames(name))
        }.generate(keyPair.private)

        // Re-read through the platform factory so the certificate exposes a usable public key.
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(generated.encoded.inputStream()) as X509Certificate
    }

    private fun trustOnly(certificate: X509Certificate) {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("probe", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers

        if (defaultSslContext == null) defaultSslContext = SSLContext.getDefault()
        SSLContext.setDefault(SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) })
    }

    private fun tlsServerFactory(keyPair: KeyPair, certificate: X509Certificate): SSLServerSocketFactory {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("probe", keyPair.private, KEY_PASSWORD, arrayOf<Certificate>(certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, KEY_PASSWORD) }
            .keyManagers

        return SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }.serverSocketFactory
    }

    /** Accepts the connection but never speaks electrum, like a non-electrum service on the port. */
    private fun startSilentServer(): Int {
        val socket = ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).also { server = it }
        thread(isDaemon = true) {
            runCatching { socket.accept().use { it.getInputStream().read() } }
        }
        return socket.localPort
    }
}
