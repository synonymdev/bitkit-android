package to.bitkit.services

import android.content.Context
import android.util.AtomicFile
import computer.iroh.BiStream
import computer.iroh.Connection
import computer.iroh.Endpoint
import computer.iroh.EndpointAddr
import computer.iroh.EndpointId
import computer.iroh.EndpointOptions
import computer.iroh.IrohAndroid
import computer.iroh.RelayMode
import computer.iroh.presetMinimal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import to.bitkit.di.IoDispatcher
import to.bitkit.ext.runSuspendCatching
import to.bitkit.models.IrohPrimaryEndpoint
import to.bitkit.utils.Logger
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

interface PrimaryPeerTransport {
    suspend fun address(nodeId: String, address: String): String
    suspend fun close()
}

internal object TcpPrimaryPeerTransport : PrimaryPeerTransport {
    override suspend fun address(nodeId: String, address: String): String {
        require(!address.startsWith("iroh:", ignoreCase = true))
        return address
    }

    override suspend fun close() = Unit
}

internal interface IrohByteStream {
    suspend fun read(limit: Int): ByteArray
    suspend fun write(bytes: ByteArray)
    fun close()
}

internal interface IrohConnector {
    suspend fun connect(endpoint: IrohPrimaryEndpoint): IrohByteStream
    suspend fun close()
}

@Singleton
class IrohPeerTransport internal constructor(
    private val connector: IrohConnector,
    private val ioDispatcher: CoroutineDispatcher,
    private val connectTimeout: Duration = CONNECT_TIMEOUT,
) : PrimaryPeerTransport {
    companion object {
        private const val TAG = "IrohPeerTransport"
        private const val BUFFER_SIZE = 65_536
        private const val MAX_CONNECTIONS = 2
        private const val MAX_PEERS = 4
        private val CONNECT_TIMEOUT = 20.seconds
    }

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(NativeIrohConnector(context, ioDispatcher), ioDispatcher)

    private class Bridge(val endpoint: IrohPrimaryEndpoint, val listener: ServerSocket, val scope: CoroutineScope) {
        val sockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()
        val links: MutableSet<IrohByteStream> = ConcurrentHashMap.newKeySet()
    }

    private val mutex = Mutex()
    private val bridges = mutableMapOf<String, Bridge>()

    override suspend fun address(nodeId: String, address: String): String = withContext(ioDispatcher) {
        if (!address.startsWith("iroh:", ignoreCase = true)) return@withContext address
        val endpoint = IrohPrimaryEndpoint.parse(address)
        mutex.withLock {
            val existing = bridges[nodeId]
            if (existing?.endpoint == endpoint) return@withLock "127.0.0.1:${existing.listener.localPort}"
            bridges.remove(nodeId)
            existing?.let { stop(it) }
            check(bridges.size < MAX_PEERS) { "Too many Iroh peer listeners" }
            val listener = ServerSocket(0, MAX_CONNECTIONS, InetAddress.getByName("127.0.0.1"))
            val bridge = Bridge(endpoint, listener, CoroutineScope(SupervisorJob() + ioDispatcher))
            bridges[nodeId] = bridge
            bridge.scope.launch { accept(bridge) }
            "127.0.0.1:${listener.localPort}"
        }
    }

    private suspend fun accept(bridge: Bridge) {
        runSuspendCatching {
            while (currentCoroutineContext().isActive) {
                val socket = bridge.listener.accept()
                if (bridge.sockets.size >= MAX_CONNECTIONS) {
                    socket.close()
                    continue
                }
                socket.tcpNoDelay = true
                bridge.sockets.add(socket)
                bridge.scope.launch { relay(bridge, socket) }
            }
        }.onFailure {
            if (!bridge.listener.isClosed) Logger.warn("Accept Iroh peer connection failed", it, context = TAG)
        }
    }

    private suspend fun relay(bridge: Bridge, socket: Socket) {
        var link: IrohByteStream? = null
        try {
            runSuspendCatching {
                val connected = withTimeout(connectTimeout) {
                    connector.connect(bridge.endpoint).also {
                        link = it
                        bridge.links.add(it)
                    }
                }
                currentCoroutineContext().ensureActive()
                coroutineScope {
                    val writer = launch {
                        try {
                            val buffer = ByteArray(BUFFER_SIZE)
                            val input = socket.getInputStream()
                            while (isActive) {
                                val size = input.read(buffer)
                                if (size < 0) break
                                if (size > 0) connected.write(buffer.copyOf(size))
                            }
                        } finally {
                            socket.close()
                            connected.close()
                        }
                    }
                    try {
                        val output = socket.getOutputStream()
                        while (isActive) {
                            val bytes = connected.read(BUFFER_SIZE)
                            if (bytes.isEmpty()) break
                            check(bytes.size <= BUFFER_SIZE)
                            output.write(bytes)
                        }
                    } finally {
                        socket.close()
                        connected.close()
                        writer.cancelAndJoin()
                    }
                }
            }.onFailure {
                if (!bridge.listener.isClosed && !socket.isClosed) {
                    Logger.warn("Relay Iroh peer connection failed", it, context = TAG)
                }
            }
        } finally {
            socket.close()
            link?.let {
                it.close()
                bridge.links.remove(it)
            }
            bridge.sockets.remove(socket)
        }
    }

    private suspend fun stop(bridge: Bridge) = withContext(NonCancellable + ioDispatcher) {
        bridge.listener.close()
        bridge.sockets.forEach { it.close() }
        bridge.links.forEach { it.close() }
        bridge.scope.coroutineContext[Job]?.cancelAndJoin()
        bridge.sockets.forEach { it.close() }
        bridge.sockets.clear()
        bridge.links.clear()
    }

    override suspend fun close() = withContext(NonCancellable + ioDispatcher) {
        mutex.withLock {
            bridges.values.forEach { stop(it) }
            bridges.clear()
            connector.close()
        }
    }
}

private class NativeIrohConnector(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : IrohConnector {
    private val mutex = Mutex()
    private var endpoint: Endpoint? = null

    override suspend fun connect(endpoint: IrohPrimaryEndpoint): IrohByteStream {
        val local = mutex.withLock {
            this.endpoint ?: bind(endpoint.relayUrl).also { this.endpoint = it }
        }
        val connection = local.connect(
            EndpointAddr(EndpointId.fromString(endpoint.endpointId), endpoint.relayUrl, emptyList()),
            "beignet/bolt8/1".toByteArray(),
        )
        try {
            connection.setMaxConcurrentBiStreams(0u)
            connection.setMaxConcurrentUniStreams(0u)
            val stream = connection.openBi()
            currentCoroutineContext().ensureActive()
            return NativeIrohByteStream(connection, stream, ioDispatcher)
        } catch (error: Throwable) {
            connection.close(0, byteArrayOf())
            throw error
        }
    }

    private suspend fun bind(relayUrl: String): Endpoint {
        IrohAndroid.installAndroidContext(context)
        val storage = AtomicFile(File(context.noBackupFilesDir, "primary-iroh.key"))
        val key = if (storage.baseFile.exists()) {
            storage.openRead().use { it.readBytes() }.also { check(it.size == 32) { "Invalid Iroh endpoint identity" } }
        } else {
            ByteArray(32).also { value ->
                SecureRandom().nextBytes(value)
                val output = storage.startWrite()
                try {
                    output.write(value)
                    storage.finishWrite(output)
                } catch (error: Throwable) {
                    storage.failWrite(output)
                    throw error
                }
            }
        }
        return try {
            Endpoint.bind(
                EndpointOptions(
                    preset = presetMinimal(),
                    secretKey = key,
                    alpns = emptyList(),
                    relayMode = RelayMode.customFromUrls(listOf(relayUrl)),
                ),
            )
        } finally { key.fill(0) }
    }

    override suspend fun close() = mutex.withLock {
        val current = endpoint
        endpoint = null
        current?.shutdown()
        Unit
    }
}

private class NativeIrohByteStream(
    private val connection: Connection,
    private val stream: BiStream,
    dispatcher: CoroutineDispatcher,
) : IrohByteStream {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            runSuspendCatching {
                connection.acceptBi()
                close()
            }
        }
        scope.launch {
            runSuspendCatching {
                connection.acceptUni()
                close()
            }
        }
    }

    override suspend fun read(limit: Int): ByteArray = stream.recv().read(limit.toUInt())
    override suspend fun write(bytes: ByteArray) = stream.send().writeAll(bytes)
    override fun close() {
        connection.close(0, byteArrayOf())
        scope.cancel()
    }
}
