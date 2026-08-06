package to.bitkit.services

import com.synonym.bitkitcore.NativeDeviceInfo
import com.synonym.bitkitcore.TrezorCallMessageResult
import com.synonym.bitkitcore.TrezorTransportReadResult
import com.synonym.bitkitcore.TrezorTransportWriteResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import to.bitkit.BuildConfig
import to.bitkit.ext.fromHex
import to.bitkit.ext.toHex
import to.bitkit.utils.Logger
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Suppress("MagicNumber")
@Singleton
class TrezorBridgeTransport(
    private val baseUrl: String,
    private val enabled: Boolean,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
    private val callReadTimeoutMs: Int = CALL_READ_TIMEOUT_MS,
) {
    @Inject
    constructor() : this(
        baseUrl = BuildConfig.TREZOR_BRIDGE_URL,
        enabled = BuildConfig.TREZOR_BRIDGE,
    )

    companion object {
        private const val TAG = "TrezorBridgeTransport"
        private const val BRIDGE_PATH_PREFIX = "bridge:"
        private const val BRIDGE_DEVICE_NAME = "Trezor Bridge Emulator"
        private const val TREZOR_VENDOR_ID = 0x1209
        private const val TREZOR_PRODUCT_ID = 0x53c1
        private const val HEADER_SIZE = 6
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val CALL_READ_TIMEOUT_MS = 120_000

        /**
         * Trezor protobuf MessageType_SignTx. This is the only call that waits
         * for on-device signing.
         */
        private const val SIGN_TX_MESSAGE_TYPE = 15
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val bridgeUrl = baseUrl.trimEnd('/')
    private val openSessions = ConcurrentHashMap<String, String>()
    private val enumeratedSessions = ConcurrentHashMap<String, String>()

    fun isBridgeDevice(path: String): Boolean {
        return enabled && path.startsWith(BRIDGE_PATH_PREFIX)
    }

    fun enumerateDevices(): List<NativeDeviceInfo> {
        if (!enabled) return emptyList()

        return runCatching {
            val response = post("/enumerate")
            json.decodeFromString<List<BridgeDevice>>(response).map {
                val bridgePath = toBridgePath(it.path)
                if (it.session == null) {
                    enumeratedSessions.remove(bridgePath)
                } else {
                    enumeratedSessions[bridgePath] = it.session
                }
                NativeDeviceInfo(
                    path = bridgePath,
                    transportType = "usb",
                    name = BRIDGE_DEVICE_NAME,
                    vendorId = TREZOR_VENDOR_ID.toUShort(),
                    productId = TREZOR_PRODUCT_ID.toUShort(),
                )
            }
        }.onSuccess {
            Logger.info("Enumerated '${it.size}' Trezor Bridge device(s)", context = TAG)
        }.getOrElse {
            Logger.warn("Failed to enumerate Trezor Bridge devices", it, context = TAG)
            emptyList()
        }
    }

    fun openDevice(path: String): TrezorTransportWriteResult {
        val rawPath = rawBridgePath(path)
        val previousSession = openSessions.remove(path) ?: enumeratedSessions[path] ?: "null"

        return runCatching {
            val response = post("/acquire/${encode(rawPath)}/${encode(previousSession)}")
            val session = json.decodeFromString<BridgeSession>(response).session
            openSessions[path] = session
            Logger.info("Opened Trezor Bridge device '$path'", context = TAG)
            TrezorTransportWriteResult(success = true, error = "", errorCode = null)
        }.getOrElse {
            Logger.warn("Failed to open Trezor Bridge device '$path'", it, context = TAG)
            TrezorTransportWriteResult(success = false, error = it.message ?: "Bridge open failed", errorCode = null)
        }
    }

    fun closeDevice(path: String): TrezorTransportWriteResult {
        val session = openSessions.remove(path)
            ?: return TrezorTransportWriteResult(success = true, error = "", errorCode = null)

        return runCatching {
            post("/release/${encode(session)}")
            // The released session must not be offered as the previous one on the next acquire:
            // the bridge holds none afterwards and rejects a stale id with 'wrong previous session'.
            enumeratedSessions.remove(path)
            Logger.info("Closed Trezor Bridge device '$path'", context = TAG)
            TrezorTransportWriteResult(success = true, error = "", errorCode = null)
        }.getOrElse {
            Logger.warn("Failed to close Trezor Bridge device '$path'", it, context = TAG)
            TrezorTransportWriteResult(success = false, error = it.message ?: "Bridge close failed", errorCode = null)
        }
    }

    fun readChunk(path: String): TrezorTransportReadResult {
        return TrezorTransportReadResult(
            success = false,
            data = byteArrayOf(),
            error = "Trezor Bridge uses callMessage for '$path'",
            errorCode = null,
        )
    }

    fun writeChunk(path: String, data: ByteArray): TrezorTransportWriteResult {
        return TrezorTransportWriteResult(
            success = false,
            error = "Trezor Bridge uses callMessage for '$path' and ignored '${data.size}' bytes",
            errorCode = null,
        )
    }

    fun callMessage(
        path: String,
        messageType: UShort,
        data: ByteArray,
    ): TrezorCallMessageResult {
        val session = openSessions[path]
            ?: return TrezorCallMessageResult(
                success = false,
                messageType = 0u.toUShort(),
                data = byteArrayOf(),
                error = "Trezor Bridge device not open: $path",
                errorCode = null,
            )

        return runCatching {
            val request = encodeFrame(messageType, data)
            val timeoutMs = if (messageType == SIGN_TX_MESSAGE_TYPE.toUShort()) callReadTimeoutMs else readTimeoutMs
            val response = post("/call/${encode(session)}", request, readTimeoutMs = timeoutMs)
            decodeFrame(response)
        }.getOrElse {
            Logger.warn("Failed to call Trezor Bridge message for '$path'", it, context = TAG)
            TrezorCallMessageResult(
                success = false,
                messageType = 0u.toUShort(),
                data = byteArrayOf(),
                error = it.message ?: "Bridge call failed",
                errorCode = null,
            )
        }
    }

    private fun encodeFrame(messageType: UShort, data: ByteArray): String {
        return ByteBuffer.allocate(HEADER_SIZE + data.size)
            .putShort(messageType.toShort())
            .putInt(data.size)
            .put(data)
            .array()
            .toHex()
    }

    private fun decodeFrame(hex: String): TrezorCallMessageResult {
        val bytes = hex.trim().fromHex()
        require(bytes.size >= HEADER_SIZE) { "Bridge response is shorter than '$HEADER_SIZE' bytes" }

        val messageType = (((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)).toUShort()
        val length = ((bytes[2].toInt() and 0xff) shl 24) or
            ((bytes[3].toInt() and 0xff) shl 16) or
            ((bytes[4].toInt() and 0xff) shl 8) or
            (bytes[5].toInt() and 0xff)
        require(bytes.size >= HEADER_SIZE + length) {
            "Bridge response payload length '$length' exceeds '${bytes.size - HEADER_SIZE}' bytes"
        }

        return TrezorCallMessageResult(
            success = true,
            messageType = messageType,
            data = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + length),
            error = "",
            errorCode = null,
        )
    }

    private fun post(path: String, body: String? = null, readTimeoutMs: Int = this.readTimeoutMs): String {
        val connection = URL("$bridgeUrl$path").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = readTimeoutMs
        connection.doInput = true

        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/plain")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }

        val statusCode = connection.responseCode
        val response = if (statusCode in 200..299) {
            connection.inputStream.bufferedReader().use { it.readText() }
        } else {
            connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
        connection.disconnect()

        check(statusCode in 200..299) {
            "Bridge request '$path' failed with HTTP '$statusCode': $response"
        }
        return response
    }

    private fun toBridgePath(path: String): String = "$BRIDGE_PATH_PREFIX$path"

    private fun rawBridgePath(path: String): String = path.removePrefix(BRIDGE_PATH_PREFIX)

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    @Serializable
    private data class BridgeDevice(
        val path: String,
        val session: String? = null,
    )

    @Serializable
    private data class BridgeSession(
        val session: String,
    )
}
