package to.bitkit.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import to.bitkit.models.IrohPrimaryEndpoint
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class IrohPeerTransportTest {
    companion object {
        private const val KEY = "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798"
        private val ADDRESS = "iroh:${"ab".repeat(32)}?relay=https%3A%2F%2Frelay.example.%2F"
    }

    private class Stream : IrohByteStream {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
        val closed = CompletableDeferred<Unit>()
        override suspend fun read(limit: Int): ByteArray = incoming.receiveCatching().getOrNull() ?: byteArrayOf()
        override suspend fun write(bytes: ByteArray) { outgoing.send(bytes) }
        override fun close() {
            incoming.close()
            outgoing.close()
            closed.complete(Unit)
        }
    }

    private class Connector : IrohConnector {
        val streams = Channel<Pair<IrohPrimaryEndpoint, Stream>>(Channel.UNLIMITED)
        var closed = false
        override suspend fun connect(endpoint: IrohPrimaryEndpoint): IrohByteStream = Stream().also {
            streams.send(endpoint to it)
        }
        override suspend fun close() { closed = true }
    }

    private fun socket(address: String): Socket = Socket("127.0.0.1", address.substringAfter(':').toInt()).apply {
        soTimeout = 5000
    }

    @Test
    fun `passes ordinary addresses through without dialing and reuses one Iroh listener`() = runBlocking {
        val connector = Connector()
        val transport = IrohPeerTransport(connector, Dispatchers.IO)
        try {
            assertEquals("node.example:9735", transport.address(KEY, "node.example:9735"))
            val address = transport.address(KEY, ADDRESS)
            assertTrue(address.startsWith("127.0.0.1:"))
            assertEquals(address, transport.address(KEY, ADDRESS))
            assertTrue(connector.streams.tryReceive().isFailure)
        } finally { transport.close() }
        assertTrue(connector.closed)
    }

    @Test
    fun `relays bounded ordered bytes both ways and closes the stream with its TCP client`() = runBlocking {
        val connector = Connector()
        val transport = IrohPeerTransport(connector, Dispatchers.IO)
        try {
            withContext(Dispatchers.IO) {
                socket(transport.address(KEY, ADDRESS)).use { client ->
                    val (endpoint, stream) = withTimeout(5000) { connector.streams.receive() }
                    assertEquals("https://relay.example./", endpoint.relayUrl)
                    val sent = ByteArray(262144) { (it % 251).toByte() }
                    val received = async {
                        val result = ArrayList<Byte>()
                        while (result.size < sent.size) {
                            result.addAll(withTimeout(5000) { stream.outgoing.receive() }.toList())
                        }
                        result.toByteArray()
                    }
                    client.getOutputStream().write(sent)
                    assertContentEquals(sent, received.await())
                    val reply = sent.reversedArray()
                    reply.toList().chunked(32768).forEach { stream.incoming.send(it.toByteArray()) }
                    assertContentEquals(reply, client.getInputStream().readNBytes(reply.size))
                    client.close()
                    withTimeout(5000) { stream.closed.await() }
                }
            }
        } finally { transport.close() }
    }

    @Test
    fun `shutdown cancels stalled dials and closes all TCP clients before listener restart`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        val connector = object : IrohConnector {
            override suspend fun connect(endpoint: IrohPrimaryEndpoint): IrohByteStream {
                count.incrementAndGet()
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            override suspend fun close() = Unit
        }
        val transport = IrohPeerTransport(connector, Dispatchers.IO)
        withContext(Dispatchers.IO) {
            val address = transport.address(KEY, ADDRESS)
            socket(address).use { client ->
                withTimeout(5000) { started.await() }
                withTimeout(5000) { transport.close() }
                withTimeout(5000) { cancelled.await() }
                assertEquals(-1, client.getInputStream().read())
                assertEquals(1, count.get())
            }
            val restarted = transport.address(KEY, ADDRESS)
            assertFalse(restarted.isBlank())
            transport.close()
        }
    }

    @Test
    fun `a dial completing after its deadline closes its acquired stream`() = runBlocking {
        val stream = Stream()
        val connector = object : IrohConnector {
            override suspend fun connect(endpoint: IrohPrimaryEndpoint): IrohByteStream {
                try {
                    awaitCancellation()
                } catch (_: CancellationException) {
                    return stream
                }
            }
            override suspend fun close() = Unit
        }
        val transport = IrohPeerTransport(connector, Dispatchers.IO, 20.milliseconds)
        try {
            withContext(Dispatchers.IO) {
                socket(transport.address(KEY, ADDRESS)).use { client ->
                    withTimeout(5000) { stream.closed.await() }
                    assertEquals(-1, client.getInputStream().read())
                }
            }
        } finally { transport.close() }
    }

    @Test
    fun `cancelled endpoint replacement never returns a cached closed listener`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val finishCleanup = CompletableDeferred<Unit>()
        val connector = object : IrohConnector {
            override suspend fun connect(endpoint: IrohPrimaryEndpoint): IrohByteStream {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        finishCleanup.await()
                    }
                }
            }
            override suspend fun close() = Unit
        }
        val transport = IrohPeerTransport(connector, Dispatchers.IO)
        try {
            withContext(Dispatchers.IO) {
                socket(transport.address(KEY, ADDRESS)).use {
                    withTimeout(5000) { started.await() }
                    val replacement = launch {
                        transport.address(KEY, ADDRESS.replace("ab".repeat(32), "cd".repeat(32)))
                    }
                    withTimeout(5000) { cleaning.await() }
                    replacement.cancel()
                    finishCleanup.complete(Unit)
                    withTimeout(5000) { replacement.cancelAndJoin() }
                    socket(transport.address(KEY, ADDRESS)).use { client -> assertTrue(client.isConnected) }
                }
            }
        } finally {
            finishCleanup.complete(Unit)
            transport.close()
        }
    }

    @Test
    fun `limits active connections and closes excess local clients`() = runBlocking {
        val connector = Connector()
        val transport = IrohPeerTransport(connector, Dispatchers.IO)
        try {
            withContext(Dispatchers.IO) {
                val address = transport.address(KEY, ADDRESS)
                socket(address).use { first ->
                    val (_, stream) = withTimeout(5000) { connector.streams.receive() }
                    socket(address).use {
                        withTimeout(5000) { connector.streams.receive() }
                        socket(address).use { excess ->
                            assertEquals(-1, excess.getInputStream().read())
                            assertTrue(connector.streams.tryReceive().isFailure)
                        }
                    }
                    first.close()
                    withTimeout(5000) { stream.closed.await() }
                }
            }
        } finally { transport.close() }
    }

    @Test
    fun `changing an endpoint retires the old connection instead of repointing its stream`() = runBlocking {
        val connector = Connector()
        val transport = IrohPeerTransport(connector, Dispatchers.IO)
        try {
            withContext(Dispatchers.IO) {
                val address = transport.address(KEY, ADDRESS)
                socket(address).use { client ->
                    val (_, old) = withTimeout(5000) { connector.streams.receive() }
                    val next = transport.address(KEY, ADDRESS.replace("ab".repeat(32), "cd".repeat(32)))
                    withTimeout(5000) { old.closed.await() }
                    assertEquals(-1, client.getInputStream().read())
                    socket(next).use {
                        val (endpoint, _) = withTimeout(5000) { connector.streams.receive() }
                        assertEquals("cd".repeat(32), endpoint.endpointId)
                    }
                }
            }
        } finally { transport.close() }
    }
}
