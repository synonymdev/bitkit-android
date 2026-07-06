package to.bitkit.services

import org.junit.After
import org.junit.Before
import org.junit.Test
import to.bitkit.ext.toHex
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("MagicNumber")
class TrezorBridgeTransportTest {

    private lateinit var server: TestHttpServer
    private var callRequestBody: String? = null
    private var releaseCalled = false

    @Before
    fun setUp() {
        server = TestHttpServer()
        server.route = { request ->
            when {
                request.method != "POST" -> TestHttpResponse("{}", statusCode = 405)
                request.path == "/enumerate" -> {
                    TestHttpResponse("""[{"path":"emulator:21324","session":null}]""")
                }
                request.path == "/acquire/emulator%3A21324/null" -> {
                    TestHttpResponse("""{"session":"session-1"}""")
                }
                request.path == "/call/session-1" -> {
                    callRequestBody = request.body
                    TestHttpResponse(frame(18u.toUShort(), byteArrayOf(0x01, 0x02, 0x03)))
                }
                request.path == "/release/session-1" -> {
                    releaseCalled = true
                    TestHttpResponse("{}")
                }
                else -> TestHttpResponse("""{"error":"unexpected"}""", statusCode = 404)
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `disabled bridge returns no devices`() {
        val sut = createSut(enabled = false)

        assertTrue(sut.enumerateDevices().isEmpty())
        assertFalse(sut.isBridgeDevice("bridge:emulator:21324"))
    }

    @Test
    fun `enumerates opens calls and releases bridge device`() {
        val sut = createSut()
        val devices = sut.enumerateDevices()
        assertTrue(server.errors.isEmpty(), server.errors.joinToString { it.message.orEmpty() })
        assertEquals(1, devices.size, "requests=${server.requests}")
        val device = devices.single()

        assertEquals("bridge:emulator:21324", device.path)
        assertEquals("usb", device.transportType)
        assertTrue(sut.isBridgeDevice(device.path))

        val openResult = sut.openDevice(device.path)
        assertTrue(openResult.success)

        val callResult = sut.callMessage(
            path = device.path,
            messageType = 55u,
            data = byteArrayOf(0x01, 0x02),
        )

        assertTrue(callResult.success)
        assertEquals(18u.toUShort(), callResult.messageType)
        assertEquals(listOf<Byte>(0x01, 0x02, 0x03), callResult.data.toList())
        assertEquals("0037000000020102", callRequestBody)

        val closeResult = sut.closeDevice(device.path)
        assertTrue(closeResult.success)
        assertTrue(releaseCalled)
    }

    @Test
    fun `call fails when bridge device was not opened`() {
        val sut = createSut()

        val result = sut.callMessage(
            path = "bridge:emulator:21324",
            messageType = 55u,
            data = byteArrayOf(),
        )

        assertFalse(result.success)
        assertTrue(result.error.contains("not open"))
    }

    @Test
    fun `malformed bridge response returns failed call`() {
        server.route = { request ->
            when {
                request.path == "/enumerate" -> {
                    TestHttpResponse("""[{"path":"emulator:21324","session":null}]""")
                }
                request.path == "/acquire/emulator%3A21324/null" -> {
                    TestHttpResponse("""{"session":"session-1"}""")
                }
                request.path == "/call/session-1" -> {
                    TestHttpResponse("0001")
                }
                else -> TestHttpResponse("{}", statusCode = 404)
            }
        }

        val sut = createSut()
        val devices = sut.enumerateDevices()
        assertTrue(server.errors.isEmpty(), server.errors.joinToString { it.message.orEmpty() })
        assertEquals(1, devices.size, "requests=${server.requests}")
        val device = devices.single()
        assertTrue(sut.openDevice(device.path).success)

        val result = sut.callMessage(device.path, 55u, byteArrayOf())

        assertFalse(result.success)
        assertTrue(result.error.contains("shorter"))
    }

    @Test
    fun `signing call uses longer read timeout than bridge management requests`() {
        server.route = { request ->
            when {
                request.path == "/enumerate" -> {
                    TestHttpResponse("""[{"path":"emulator:21324","session":null}]""")
                }
                request.path == "/acquire/emulator%3A21324/null" -> {
                    TestHttpResponse("""{"session":"session-1"}""")
                }
                request.path == "/call/session-1" -> {
                    TestHttpResponse(
                        body = frame(18u.toUShort(), byteArrayOf(0x01)),
                        delayMs = 200,
                    )
                }
                else -> TestHttpResponse("{}", statusCode = 404)
            }
        }

        val sut = createSut(readTimeoutMs = 50, callReadTimeoutMs = 1_000)
        val device = sut.enumerateDevices().single()
        assertTrue(sut.openDevice(device.path).success)

        val result = sut.callMessage(device.path, 15u, byteArrayOf())

        assertTrue(result.success)
        assertEquals(18u.toUShort(), result.messageType)
        assertEquals(listOf<Byte>(0x01), result.data.toList())
    }

    @Test
    fun `button ack call uses longer read timeout than bridge management requests`() {
        server.route = { request ->
            when {
                request.path == "/enumerate" -> {
                    TestHttpResponse("""[{"path":"emulator:21324","session":null}]""")
                }
                request.path == "/acquire/emulator%3A21324/null" -> {
                    TestHttpResponse("""{"session":"session-1"}""")
                }
                request.path == "/call/session-1" -> {
                    TestHttpResponse(
                        body = frame(18u.toUShort(), byteArrayOf(0x01)),
                        delayMs = 200,
                    )
                }
                else -> TestHttpResponse("{}", statusCode = 404)
            }
        }

        val sut = createSut(readTimeoutMs = 50, callReadTimeoutMs = 1_000)
        val device = sut.enumerateDevices().single()
        assertTrue(sut.openDevice(device.path).success)

        val result = sut.callMessage(device.path, 27u, byteArrayOf())

        assertTrue(result.success)
        assertEquals(18u.toUShort(), result.messageType)
        assertEquals(listOf<Byte>(0x01), result.data.toList())
    }

    @Test
    fun `non signing call uses bridge management read timeout`() {
        server.route = { request ->
            when {
                request.path == "/enumerate" -> {
                    TestHttpResponse("""[{"path":"emulator:21324","session":null}]""")
                }
                request.path == "/acquire/emulator%3A21324/null" -> {
                    TestHttpResponse("""{"session":"session-1"}""")
                }
                request.path == "/call/session-1" -> {
                    TestHttpResponse(
                        body = frame(18u.toUShort(), byteArrayOf(0x01)),
                        delayMs = 200,
                    )
                }
                else -> TestHttpResponse("{}", statusCode = 404)
            }
        }

        val sut = createSut(readTimeoutMs = 50, callReadTimeoutMs = 1_000)
        val device = sut.enumerateDevices().single()
        assertTrue(sut.openDevice(device.path).success)

        val result = sut.callMessage(device.path, 55u, byteArrayOf())

        assertFalse(result.success)
    }

    private fun createSut(
        enabled: Boolean = true,
        readTimeoutMs: Int = 30_000,
        callReadTimeoutMs: Int = 120_000,
    ): TrezorBridgeTransport {
        return TrezorBridgeTransport(
            baseUrl = "http://127.0.0.1:${server.port}",
            enabled = enabled,
            readTimeoutMs = readTimeoutMs,
            callReadTimeoutMs = callReadTimeoutMs,
        )
    }

    private fun frame(messageType: UShort, data: ByteArray): String {
        return ByteBuffer.allocate(6 + data.size)
            .putShort(messageType.toShort())
            .putInt(data.size)
            .put(data)
            .array()
            .toHex()
    }

    private data class TestHttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private data class TestHttpResponse(
        val body: String,
        val statusCode: Int = 200,
        val delayMs: Long = 0L,
    )

    private class TestHttpServer {
        private val socket = ServerSocket(0)
        private lateinit var worker: Thread

        val port: Int = socket.localPort

        @Volatile
        var route: (TestHttpRequest) -> TestHttpResponse = { TestHttpResponse("{}", statusCode = 404) }
        val errors = Collections.synchronizedList(mutableListOf<Throwable>())
        val requests = Collections.synchronizedList(mutableListOf<String>())

        fun start() {
            worker = thread(start = true) {
                while (!socket.isClosed) {
                    runCatching { socket.accept().use(::handle) }.onFailure { errors.add(it) }
                }
            }
        }

        fun stop() {
            socket.close()
            worker.join(1_000)
        }

        private fun handle(client: Socket) {
            val input = client.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            val requestParts = requestLine.split(" ")
            val headers = mutableMapOf<String, String>()

            while (true) {
                val line = input.readLine()
                if (line.isNullOrEmpty()) break
                val separator = line.indexOf(":")
                if (separator > 0) {
                    headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
                }
            }

            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            val body = CharArray(contentLength)
            if (contentLength > 0) {
                input.read(body, 0, contentLength)
            }

            val request = TestHttpRequest(
                method = requestParts.getOrElse(0) { "" },
                path = requestParts.getOrElse(1) { "" },
                body = String(body),
            )
            requests.add("${request.method} ${request.path}")
            val response = route(request)
            if (response.delayMs > 0) Thread.sleep(response.delayMs)
            val responseBytes = response.body.toByteArray()
            val responseHeaders = (
                "HTTP/1.1 ${response.statusCode} OK\r\n" +
                    "Content-Length: ${responseBytes.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray()
            client.getOutputStream().use {
                it.write(responseHeaders)
                it.write(responseBytes)
            }
        }
    }
}
