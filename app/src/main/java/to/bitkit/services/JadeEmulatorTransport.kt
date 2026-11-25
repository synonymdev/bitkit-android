package to.bitkit.services

import com.synonym.bitkitcore.JadeNativeDevice
import com.synonym.bitkitcore.JadeTransportErrorCode
import com.synonym.bitkitcore.JadeTransportKind
import com.synonym.bitkitcore.JadeTransportReadResult
import com.synonym.bitkitcore.JadeTransportResult
import to.bitkit.BuildConfig
import to.bitkit.utils.Logger
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Serial over TCP to the Jade QEMU emulator from bitkit-docker (`jade-emulator`), so the Jade flows
 * run without a device. Debug builds only, behind `JADE_EMULATOR`; the device shows up in scans as
 * one more serial Jade at a `tcp:` path.
 */
@Singleton
class JadeEmulatorTransport(
    private val address: String,
    private val enabled: Boolean,
) {
    @Inject
    constructor() : this(
        address = BuildConfig.JADE_EMULATOR_URL,
        enabled = BuildConfig.JADE_EMULATOR,
    )

    companion object {
        private const val TAG = "JadeEmulatorTransport"
        private const val PATH_PREFIX = "tcp:"
        private const val DEVICE_NAME = "Jade Emulator"
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val READ_TIMEOUT_MAX_MS = 1_000L
        private const val READ_BUFFER_BYTES = 4_096
    }

    private val path = "$PATH_PREFIX$address"
    private val sockets = ConcurrentHashMap<String, Socket>()

    fun isEmulatorPath(path: String): Boolean = enabled && path.startsWith(PATH_PREFIX)

    /** Lists the emulator only while its port answers, so a stopped container is not offered. */
    fun scanDevices(): List<JadeNativeDevice> {
        if (!enabled) return emptyList()
        if (!sockets.containsKey(path) && !isReachable()) return emptyList()
        return listOf(
            JadeNativeDevice(path = path, transport = JadeTransportKind.SERIAL, name = DEVICE_NAME, serialNumber = null)
        )
    }

    fun openDevice(path: String): JadeTransportResult {
        closeDevice(path)
        return runCatching {
            val socket = connect()
            socket.tcpNoDelay = true
            sockets[path] = socket
            Logger.info("Opened Jade emulator '$path'", context = TAG)
            ok()
        }.getOrElse {
            Logger.warn("Failed to open Jade emulator '$path'", it, context = TAG)
            fail(it.message ?: "Emulator connect failed", JadeTransportErrorCode.NOT_CONNECTED)
        }
    }

    fun closeDevice(path: String): JadeTransportResult {
        sockets.remove(path)?.let { socket ->
            runCatching { socket.close() }
            Logger.info("Closed Jade emulator '$path'", context = TAG)
        }
        return ok()
    }

    fun closeAll() = sockets.keys.toList().forEach { closeDevice(it) }

    fun writeChunk(path: String, data: ByteArray): JadeTransportResult {
        val socket = sockets[path] ?: return fail("Device not open: '$path'", JadeTransportErrorCode.NOT_CONNECTED)
        return try {
            socket.getOutputStream().apply {
                write(data)
                flush()
            }
            ok()
        } catch (e: IOException) {
            Logger.warn("Jade emulator write failed", e, context = TAG)
            fail(e.message ?: "Emulator write failed", JadeTransportErrorCode.DISCONNECTED)
        }
    }

    fun readChunk(path: String, timeoutMs: UInt): JadeTransportReadResult {
        val socket = sockets[path]
            ?: return readFail("Device not open: '$path'", JadeTransportErrorCode.NOT_CONNECTED)
        return try {
            socket.soTimeout = timeoutMs.toLong().coerceIn(1L, READ_TIMEOUT_MAX_MS).toInt()
            val buffer = ByteArray(READ_BUFFER_BYTES)
            when (val read = socket.getInputStream().read(buffer)) {
                -1 -> readFail("Jade emulator closed the connection", JadeTransportErrorCode.DISCONNECTED)
                else -> readOk(buffer.copyOf(read))
            }
        } catch (_: SocketTimeoutException) {
            // Nothing received yet is the normal state while the device works or waits.
            readOk(byteArrayOf())
        } catch (e: IOException) {
            Logger.warn("Jade emulator read failed", e, context = TAG)
            readFail(e.message ?: "Emulator read failed", JadeTransportErrorCode.DISCONNECTED)
        }
    }

    private fun isReachable(): Boolean = runCatching { connect().close() }.isSuccess

    private fun connect(): Socket {
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toInt()
        return Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
    }

    private fun ok() = JadeTransportResult(success = true, error = "", errorCode = null)

    private fun fail(error: String, code: JadeTransportErrorCode?) =
        JadeTransportResult(success = false, error = error, errorCode = code)

    private fun readOk(data: ByteArray) =
        JadeTransportReadResult(success = true, data = data, error = "", errorCode = null)

    private fun readFail(error: String, code: JadeTransportErrorCode?) =
        JadeTransportReadResult(success = false, data = byteArrayOf(), error = error, errorCode = code)
}
