package to.bitkit.services

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.synonym.bitkitcore.NativeDeviceInfo
import com.synonym.bitkitcore.TrezorCallMessageResult
import com.synonym.bitkitcore.TrezorTransportCallback
import com.synonym.bitkitcore.TrezorTransportReadResult
import com.synonym.bitkitcore.TrezorTransportWriteResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.bitkit.ext.bluetoothManager
import to.bitkit.ext.usbManager
import to.bitkit.models.TransportType
import to.bitkit.utils.Logger
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transport callback implementation for Trezor communication.
 *
 * This class implements the [TrezorTransportCallback] interface which is called by
 * the Rust bitkit-core module for USB/Bluetooth I/O operations.
 *
 * USB communication uses 64-byte chunks, Bluetooth uses 244-byte chunks.
 */
@Suppress("LargeClass")
@Singleton
class TrezorTransport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridgeTransport: TrezorBridgeTransport,
) : TrezorTransportCallback {

    companion object {
        private const val TAG = "TrezorTransport"
        private const val ACTION_USB_PERMISSION = "to.bitkit.USB_PERMISSION"

        // USB constants
        private const val USB_CHUNK_SIZE = 64
        private const val USB_PERMISSION_TIMEOUT_MS = 60_000L
        private const val TREZOR_VENDOR_ID_1 = 0x1209
        private const val TREZOR_VENDOR_ID_2 = 0x534c

        // BLE constants
        private const val BLE_CHUNK_SIZE = 244
        private val SERVICE_UUID = UUID.fromString("8c000001-a59b-4d58-a9ad-073df69fa1b1")
        private val WRITE_CHAR_UUID = UUID.fromString("8c000002-a59b-4d58-a9ad-073df69fa1b1")
        private val NOTIFY_CHAR_UUID = UUID.fromString("8c000003-a59b-4d58-a9ad-073df69fa1b1")
        private val PUSH_CHAR_UUID = UUID.fromString("8c000004-a59b-4d58-a9ad-073df69fa1b1")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Timeouts
        private const val READ_TIMEOUT_MS = 5000
        private const val WRITE_TIMEOUT_MS = 5000
        private const val SCAN_DURATION_MS = 3000L
        private const val CONNECTION_TIMEOUT_MS = 10000L
        private const val BLE_READ_TIMEOUT_MS = 5000L
        private const val DISCONNECT_TIMEOUT_MS = 3000L
        private const val PAIRING_CODE_TIMEOUT_MS = 120000L // 2 minutes to enter code

        // BLE write retry settings
        private const val BLE_WRITE_RETRY_COUNT = 3
        private const val BLE_WRITE_RETRY_DELAY_MS = 100L
        private const val BLE_WRITE_INTER_DELAY_MS = 20L
        private const val BLE_CONNECTION_STABILIZATION_MS = 1000L
        private const val BLE_CCCD_STABILIZATION_MS = 200L

        // BLE bonding constants
        private const val MAX_BOND_POLL_ATTEMPTS = 60
        private const val BOND_POLL_INTERVAL_MS = 500L
    }

    private val usbManager: UsbManager by lazy { context.usbManager }

    private val bluetoothManager: BluetoothManager by lazy { context.bluetoothManager }

    private val credentialDir: File by lazy {
        File(context.filesDir, "trezor-thp-credentials").also { it.mkdirs() }
    }

    private val userInitiatedCloseSet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var requestUsbPermissionEnabled = true

    @Volatile
    private var bluetoothScanningEnabled = true

    private val _externalDisconnect = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val externalDisconnect: SharedFlow<String> = _externalDisconnect

    private val _transportRestored = MutableSharedFlow<TransportType>(extraBufferCapacity = 1)

    /** Emits the transport that became available again: Bluetooth back on or a Trezor plugged in. */
    val transportRestored: SharedFlow<TransportType> = _transportRestored

    @Volatile
    private var espMigrated = false

    @Suppress("TooGenericExceptionCaught")
    private fun ensureEspMigration() {
        if (espMigrated) return
        synchronized(this) {
            if (espMigrated) return
            espMigrated = true
            try {
                val espPrefs = context.getSharedPreferences(
                    "trezor_thp_credentials",
                    Context.MODE_PRIVATE,
                )
                val allEntries = espPrefs.all
                if (allEntries.isEmpty()) return
                var migrated = 0
                for ((key, value) in allEntries) {
                    if (!key.startsWith("thp_credential_") || value !is String) continue
                    val sanitizedId = key.removePrefix("thp_credential_")
                    val file = File(credentialDir, "$sanitizedId.json")
                    file.writeText(value)
                    migrated++
                }
                if (migrated > 0) {
                    espPrefs.edit(commit = true) { clear() }
                    Logger.info("Migrated '$migrated' THP credentials from SharedPreferences to files", context = TAG)
                }
            } catch (e: Exception) {
                Logger.warn("ESP migration failed (may be inaccessible)", e, context = TAG)
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager.adapter
    }

    /**
     * Feeds Bluetooth-off and USB-unplug events into the same [externalDisconnect]
     * flow so the repo clears the connected device and the UI connection indicator
     * reflects reality in real time. Bluetooth coming back on or a Trezor being
     * plugged in emits [transportRestored] so the repo can silently reconnect.
     */
    private val connectionStateReceiver = ConnectionStateReceiver(
        onBluetoothOff = {
            bleConnections.keys.toList().forEach { path ->
                bleConnections[path]?.isConnected = false
                emitExternalDisconnect(path)
            }
        },
        onBluetoothOn = { _transportRestored.tryEmit(TransportType.BLUETOOTH) },
        onUsbDetached = { path ->
            if (path in usbConnections.keys) emitExternalDisconnect(path)
        },
        onUsbAttached = { device ->
            if (isTrezorDevice(device)) _transportRestored.tryEmit(TransportType.USB)
        },
    )

    private fun emitExternalDisconnect(path: String) {
        if (!userInitiatedCloseSet.remove(path)) {
            _externalDisconnect.tryEmit(path)
        }
    }

    init {
        connectionStateReceiver.register(context)
    }

    private val usbConnections = ConcurrentHashMap<String, UsbOpenDevice>()

    private val bleConnections = ConcurrentHashMap<String, BleConnection>()
    private val discoveredBleDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val optionScopeMutex = Mutex()

    private data class UsbOpenDevice(
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val readEndpoint: UsbEndpoint,
        val writeEndpoint: UsbEndpoint,
    )

    private data class BleConnection(
        val gatt: BluetoothGatt,
        var readCharacteristic: BluetoothGattCharacteristic?,
        var writeCharacteristic: BluetoothGattCharacteristic?,
        val readQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(),
        @Volatile var isConnected: Boolean = false,
        @Volatile var connectionLatch: CountDownLatch? = null,
        @Volatile var writeLatch: CountDownLatch? = null,
        @Volatile var disconnectLatch: CountDownLatch? = null,
        @Volatile var writeStatus: Int = BluetoothGatt.GATT_SUCCESS,
    )

    suspend fun <T> withUsbPermissionRequestsEnabled(
        enabled: Boolean,
        block: suspend () -> T,
    ): T = optionScopeMutex.withLock {
        val previous = requestUsbPermissionEnabled
        requestUsbPermissionEnabled = enabled
        try {
            block()
        } finally {
            requestUsbPermissionEnabled = previous
        }
    }

    suspend fun <T> withBluetoothScanningEnabled(
        enabled: Boolean,
        block: suspend () -> T,
    ): T = optionScopeMutex.withLock {
        val previous = bluetoothScanningEnabled
        bluetoothScanningEnabled = enabled
        try {
            block()
        } finally {
            bluetoothScanningEnabled = previous
        }
    }

    override fun enumerateDevices(): List<NativeDeviceInfo> {
        val devices = mutableListOf<NativeDeviceInfo>()

        runCatching {
            usbManager.deviceList.values
                .filter { isTrezorDevice(it) }
                .map { device ->
                    NativeDeviceInfo(
                        path = device.deviceName,
                        transportType = "usb",
                        name = runCatching { device.productName }.getOrNull(),
                        vendorId = device.vendorId.toUShort(),
                        productId = device.productId.toUShort(),
                    )
                }
        }.onSuccess {
            devices.addAll(it)
            Logger.debug("USB enumerate found '${it.size}' Trezor device(s)", context = TAG)
        }.onFailure {
            Logger.error("USB enumerate failed", it, context = TAG)
        }

        if (bluetoothScanningEnabled) {
            runCatching {
                enumerateBleDevices()
            }.onSuccess {
                devices.addAll(it)
                Logger.debug("BLE enumerate found '${it.size}' Trezor device(s)", context = TAG)
            }.onFailure {
                Logger.error("BLE enumerate failed", it, context = TAG)
            }
        } else {
            Logger.debug("Skipped BLE enumerate while Bluetooth scanning is disabled", context = TAG)
        }

        val bridgeDevices = bridgeTransport.enumerateDevices()
        devices.addAll(bridgeDevices)
        if (bridgeDevices.isNotEmpty()) {
            Logger.info("Found '${bridgeDevices.size}' Trezor Bridge device(s)", context = TAG)
        }

        Logger.info("Total enumerate found '${devices.size}' Trezor device(s)", context = TAG)
        val summary = devices.map { "${it.path} (${it.transportType})" }
        TrezorDebugLog.log("ENUM", "Found ${devices.size} devices: $summary")
        return devices
    }

    override fun openDevice(path: String): TrezorTransportWriteResult {
        TrezorDebugLog.log("OPEN", "openDevice: $path")
        return if (bridgeTransport.isBridgeDevice(path)) {
            bridgeTransport.openDevice(path)
        } else if (isBleDevice(path)) {
            openBleDevice(path)
        } else {
            openUsbDevice(path)
        }
    }

    override fun closeDevice(path: String): TrezorTransportWriteResult {
        TrezorDebugLog.log("CLOSE", "closeDevice: $path")
        return if (bridgeTransport.isBridgeDevice(path)) {
            bridgeTransport.closeDevice(path)
        } else if (isBleDevice(path)) {
            closeBleDevice(path)
        } else {
            closeUsbDevice(path)
        }
    }

    override fun readChunk(path: String): TrezorTransportReadResult {
        return if (bridgeTransport.isBridgeDevice(path)) {
            bridgeTransport.readChunk(path)
        } else if (isBleDevice(path)) {
            readBleChunk(path)
        } else {
            readUsbChunk(path)
        }
    }

    override fun writeChunk(path: String, data: ByteArray): TrezorTransportWriteResult {
        return if (bridgeTransport.isBridgeDevice(path)) {
            bridgeTransport.writeChunk(path, data)
        } else if (isBleDevice(path)) {
            writeBleChunk(path, data)
        } else {
            writeUsbChunk(path, data)
        }
    }

    override fun getChunkSize(path: String): UInt {
        return if (bridgeTransport.isBridgeDevice(path)) {
            USB_CHUNK_SIZE.toUInt()
        } else if (isBleDevice(path)) {
            BLE_CHUNK_SIZE.toUInt()
        } else {
            USB_CHUNK_SIZE.toUInt()
        }
    }

    override fun callMessage(
        path: String,
        messageType: UShort,
        data: ByteArray,
    ): TrezorCallMessageResult? {
        if (bridgeTransport.isBridgeDevice(path)) {
            return bridgeTransport.callMessage(path, messageType, data)
        }

        Logger.debug(
            "Delegating callMessage for '$path', type='$messageType' to core THP handling",
            context = TAG,
        )
        return null
    }

    override fun getPairingCode(): String {
        TrezorDebugLog.log("PAIR", ">>> PAIRING CODE REQUESTED - Device requires re-pairing! <<<")
        Logger.info("Requested pairing code from user", context = TAG)
        Logger.info("Asked user to read the 6-digit code from Trezor screen", context = TAG)

        val latch = CountDownLatch(1)

        synchronized(pairingCodeLock) {
            pairingCodeResult = null
            pairingCodeRequest = PairingCodeRequest(isRequested = true, latch = latch)
            _needsPairingCode.update { true }
        }

        val result = try {
            val received = latch.await(PAIRING_CODE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            synchronized(pairingCodeLock) {
                val result = if (received) {
                    pairingCodeResult ?: PairingCodeResult.Cancelled
                } else {
                    PairingCodeResult.TimedOut
                }
                clearPairingCodeRequest()
                result
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            synchronized(pairingCodeLock) {
                clearPairingCodeRequest()
            }
            PairingCodeResult.Interrupted(e)
        }

        return when (result) {
            is PairingCodeResult.Submitted -> {
                Logger.info("Received pairing code (len='${result.code.length}')", context = TAG)
                result.code
            }
            PairingCodeResult.Cancelled -> {
                Logger.info("Cancelled pairing code entry", context = TAG)
                ""
            }
            PairingCodeResult.TimedOut -> {
                Logger.warn("Timed out waiting for pairing code entry", context = TAG)
                ""
            }
            is PairingCodeResult.Interrupted -> {
                Logger.error("Interrupted pairing code wait", result.error, context = TAG)
                ""
            }
        }
    }

    data class PairingCodeRequest(
        val isRequested: Boolean = false,
        val latch: CountDownLatch? = null,
    )

    @Volatile
    private var pairingCodeRequest: PairingCodeRequest = PairingCodeRequest()

    @Volatile
    private var pairingCodeResult: PairingCodeResult? = null

    private val pairingCodeLock = Object()

    private sealed interface PairingCodeResult {
        data class Submitted(val code: String) : PairingCodeResult

        data object Cancelled : PairingCodeResult

        data object TimedOut : PairingCodeResult

        data class Interrupted(val error: InterruptedException) : PairingCodeResult
    }

    private fun clearPairingCodeRequest() {
        pairingCodeRequest = PairingCodeRequest()
        pairingCodeResult = null
        _needsPairingCode.update { false }
    }

    /**
     * Flow to observe when a pairing code is needed.
     * UI should show a dialog when this is true.
     */
    private val _needsPairingCode = MutableStateFlow(false)
    val needsPairingCode: StateFlow<Boolean> = _needsPairingCode

    /**
     * Submit a pairing code from the UI.
     * This unblocks the getPairingCode() call waiting on the Rust side.
     */
    fun submitPairingCode(code: String) {
        synchronized(pairingCodeLock) {
            Logger.info("Submitted pairing code (len='${code.length}')", context = TAG)
            pairingCodeResult = PairingCodeResult.Submitted(code)
            _needsPairingCode.update { false }
            pairingCodeRequest.latch?.countDown()
        }
    }

    fun cancelPairingCode() {
        synchronized(pairingCodeLock) {
            Logger.info("Cancelled pairing code entry", context = TAG)
            pairingCodeResult = PairingCodeResult.Cancelled
            _needsPairingCode.update { false }
            pairingCodeRequest.latch?.countDown()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun saveThpCredential(deviceId: String, credentialJson: String): Boolean {
        ensureEspMigration()
        return try {
            val file = credentialFile(deviceId)
            TrezorDebugLog.log("SAVE", "saveThpCredential called for: $deviceId")
            TrezorDebugLog.log("SAVE", "File path: ${file.absolutePath}")
            TrezorDebugLog.log("SAVE", "Credential length: ${credentialJson.length}")

            if (credentialJson.isEmpty()) {
                val existed = file.exists()
                val deleted = !existed || file.delete()
                TrezorDebugLog.log("SAVE", "CLEARED credential (file existed=$existed)")
                if (!deleted) {
                    Logger.warn("Clear THP credential file failed for '${file.absolutePath}'", context = TAG)
                    return false
                }
                Logger.info(
                    "Cleared THP credential for device: '$deviceId' (path='${file.absolutePath}')",
                    context = TAG,
                )
                return true
            }

            file.writeText(credentialJson)

            val verifyExists = file.exists()
            val verifySize = if (verifyExists) file.length() else 0
            TrezorDebugLog.log(
                "SAVE",
                "Wrote ${credentialJson.length} chars -> verify: exists=$verifyExists, size=$verifySize",
            )
            if (!verifyExists || verifySize == 0L) {
                TrezorDebugLog.log("SAVE", "WARNING: File verification FAILED after write!")
                return false
            }

            Logger.info(
                "Saving THP credential to: '${file.absolutePath}' (${credentialJson.length} chars)",
                context = TAG,
            )
            true
        } catch (e: Exception) {
            TrezorDebugLog.log("SAVE", "EXCEPTION: ${e.message}")
            Logger.error("Failed to save THP credential", e, context = TAG)
            false
        }
    }

    override fun logDebug(tag: String, message: String) {
        TrezorDebugLog.log("RUST:$tag", message)
    }

    @Suppress("TooGenericExceptionCaught")
    override fun loadThpCredential(deviceId: String): String? {
        ensureEspMigration()
        return try {
            val file = credentialFile(deviceId)
            val exists = file.exists()
            val size = if (exists) file.length() else 0
            TrezorDebugLog.log("LOAD", "loadThpCredential for: $deviceId")
            TrezorDebugLog.log("LOAD", "File: ${file.absolutePath}, exists=$exists, size=$size")

            val allFiles = credentialDir.listFiles()?.map { "${it.name} (${it.length()}b)" } ?: emptyList()
            TrezorDebugLog.log("LOAD", "All credential files: $allFiles")

            Logger.info(
                "Loading THP credential from: '${file.absolutePath}', exists='$exists', size='$size'",
                context = TAG,
            )
            if (exists) {
                val json = file.readText()
                TrezorDebugLog.log("LOAD", "Loaded ${json.length} chars, blank=${json.isBlank()}")
                if (json.isBlank()) {
                    TrezorDebugLog.log("LOAD", "WARNING: File exists but is blank! Returning null.")
                    null
                } else {
                    Logger.info("Loaded THP credential for device: '$deviceId' (${json.length} chars)", context = TAG)
                    json
                }
            } else {
                TrezorDebugLog.log("LOAD", "No credential file found -> returning null")
                Logger.debug("No stored THP credential for device: '$deviceId'", context = TAG)
                null
            }
        } catch (e: Exception) {
            TrezorDebugLog.log("LOAD", "EXCEPTION: ${e.message}")
            Logger.error("Failed to load THP credential", e, context = TAG)
            null
        }
    }

    fun clearDeviceCredential(deviceId: String) {
        runCatching {
            val file = credentialFile(deviceId)
            TrezorDebugLog.log("CLEAR", "clearDeviceCredential for: $deviceId, exists=${file.exists()}")
            file.delete()
            Logger.info("Cleared device credential for: '$deviceId'", context = TAG)
        }.onFailure {
            TrezorDebugLog.log("CLEAR", "EXCEPTION: ${it.message}")
            Logger.error("Failed to clear device credential", it, context = TAG)
        }
    }

    private fun credentialFile(deviceId: String): File {
        val sanitizedId = deviceId.replace(":", "_").replace("/", "_")
        return File(credentialDir, "$sanitizedId.json")
    }

    /**
     * Request USB permission for a device and block until the user responds.
     * Returns true if permission was granted, false otherwise.
     *
     * This uses a BroadcastReceiver + CountDownLatch pattern because openDevice
     * runs on a background thread (Rust FFI callback), not the main thread.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun requestUsbPermission(device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == ACTION_USB_PERMISSION) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(ACTION_USB_PERMISSION).apply { setPackage(context.packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            Logger.info("Requesting USB permission for '${device.deviceName}'", context = TAG)
            usbManager.requestPermission(device, permissionIntent)

            val responded = latch.await(USB_PERMISSION_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!responded) {
                Logger.warn("USB permission request timed out", context = TAG)
                return false
            }

            val status = if (granted) "granted" else "denied"
            Logger.info("USB permission '$status' for '${device.deviceName}'", context = TAG)
            return granted
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    private data class UsbEndpoints(val read: UsbEndpoint, val write: UsbEndpoint)

    private fun findUsbEndpoints(usbInterface: UsbInterface): UsbEndpoints? {
        var readEndpoint: UsbEndpoint? = null
        var writeEndpoint: UsbEndpoint? = null

        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)
            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN if endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT -> {
                    readEndpoint = endpoint
                }
                UsbConstants.USB_DIR_OUT if endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT -> {
                    writeEndpoint = endpoint
                }
            }
        }

        if (readEndpoint == null || writeEndpoint == null) return null
        return UsbEndpoints(read = readEndpoint, write = writeEndpoint)
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun openUsbDevice(path: String): TrezorTransportWriteResult {
        return try {
            closeUsbDevice(path)

            val device = usbManager.deviceList[path]
                ?: return TrezorTransportWriteResult(success = false, error = "Device not found: $path")

            if (!usbManager.hasPermission(device)) {
                if (!requestUsbPermissionEnabled) {
                    Logger.info("Skipped USB permission request for '$path'", context = TAG)
                    return TrezorTransportWriteResult(
                        success = false,
                        error = "USB permission missing for '$path'",
                    )
                }
                if (!requestUsbPermission(device)) {
                    return TrezorTransportWriteResult(
                        success = false,
                        error = "USB permission denied for '$path'",
                    )
                }
            }

            val connection = usbManager.openDevice(device)
                ?: return TrezorTransportWriteResult(success = false, error = "Failed to open device: $path")

            val usbInterface = device.getInterface(0)
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                return TrezorTransportWriteResult(success = false, error = "Failed to claim interface")
            }

            val endpoints = findUsbEndpoints(usbInterface)
            if (endpoints == null) {
                connection.releaseInterface(usbInterface)
                connection.close()
                return TrezorTransportWriteResult(
                    success = false,
                    error = "Could not find required endpoints",
                )
            }

            usbConnections[path] = UsbOpenDevice(
                connection,
                usbInterface,
                endpoints.read,
                endpoints.write,
            )
            Logger.info("USB device opened: '$path'", context = TAG)
            TrezorTransportWriteResult(success = true, error = "")
        } catch (e: Exception) {
            Logger.error("USB open failed", e, context = TAG)
            TrezorTransportWriteResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeUsbDevice(path: String): TrezorTransportWriteResult {
        return try {
            val openDevice = usbConnections.remove(path)
                ?: return TrezorTransportWriteResult(success = true, error = "")

            openDevice.connection.releaseInterface(openDevice.usbInterface)
            openDevice.connection.close()
            Logger.info("USB device closed: '$path'", context = TAG)
            TrezorTransportWriteResult(success = true, error = "")
        } catch (e: Exception) {
            Logger.error("USB close failed", e, context = TAG)
            TrezorTransportWriteResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readUsbChunk(path: String): TrezorTransportReadResult {
        return try {
            val openDevice = usbConnections[path]
                ?: return TrezorTransportReadResult(
                    success = false,
                    data = byteArrayOf(),
                    error = "Device not open: $path",
                )

            // Synchronous transfer on purpose: the async UsbRequest API requires
            // cancelling and reaping a timed-out request before closing it; closing
            // with the URB still queued frees memory the kernel later writes into
            // (native SIGSEGV in libusbhost once the device finally responds).
            val buffer = ByteArray(USB_CHUNK_SIZE)
            val bytesRead = openDevice.connection.bulkTransfer(
                openDevice.readEndpoint,
                buffer,
                buffer.size,
                READ_TIMEOUT_MS,
            )
            if (bytesRead < 0) {
                return TrezorTransportReadResult(
                    success = false,
                    data = byteArrayOf(),
                    error = "USB read timed out",
                )
            }

            Logger.debug("USB read '$bytesRead' bytes from '$path'", context = TAG)
            TrezorTransportReadResult(success = true, data = buffer.copyOf(bytesRead), error = "")
        } catch (e: Exception) {
            Logger.error("USB read failed", e, context = TAG)
            TrezorTransportReadResult(success = false, data = byteArrayOf(), error = e.message ?: "Unknown error")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeUsbChunk(path: String, data: ByteArray): TrezorTransportWriteResult {
        return try {
            val openDevice = usbConnections[path]
                ?: return TrezorTransportWriteResult(success = false, error = "Device not open: $path")

            val bytesWritten = openDevice.connection.bulkTransfer(
                openDevice.writeEndpoint,
                data,
                data.size,
                WRITE_TIMEOUT_MS,
            )
            if (bytesWritten != data.size) {
                return TrezorTransportWriteResult(success = false, error = "USB write timed out")
            }

            Logger.debug("USB wrote '${data.size}' bytes to '$path'", context = TAG)
            TrezorTransportWriteResult(success = true, error = "")
        } catch (e: Exception) {
            Logger.error("USB write failed", e, context = TAG)
            TrezorTransportWriteResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    @SuppressLint("MissingPermission")
    private fun enumerateBleDevices(): List<NativeDeviceInfo> {
        if (bluetoothAdapter?.isEnabled != true) {
            Logger.warn("Bluetooth is not enabled", context = TAG)
            return emptyList()
        }

        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return emptyList()

        discoveredBleDevices.clear()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(scanFilter), scanSettings, bleScanCallback)
        Logger.debug("BLE scan started", context = TAG)

        Thread.sleep(SCAN_DURATION_MS)

        scanner.stopScan(bleScanCallback)
        Logger.debug("BLE scan stopped", context = TAG)

        return discoveredBleDevices.values.map { device ->
            NativeDeviceInfo(
                path = "ble:${device.address}",
                transportType = "bluetooth",
                name = device.name ?: "Trezor",
                vendorId = null,
                productId = null,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            if (!discoveredBleDevices.containsKey(address)) {
                discoveredBleDevices[address] = device
                Logger.debug("BLE device found: '$address' ('${device.name}')", context = TAG)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.error("BLE scan failed: '$errorCode'", context = TAG)
        }
    }

    @SuppressLint("MissingPermission")
    private fun waitForBonding(
        device: BluetoothDevice,
        address: String,
    ): TrezorTransportWriteResult? {
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Logger.info("Device not bonded, initiating bonding: '$address'", context = TAG)
            if (!device.createBond()) {
                return TrezorTransportWriteResult(success = false, error = "Failed to initiate bonding")
            }
            var bondAttempts = 0
            while (device.bondState != BluetoothDevice.BOND_BONDED && bondAttempts < MAX_BOND_POLL_ATTEMPTS) {
                Thread.sleep(BOND_POLL_INTERVAL_MS)
                bondAttempts++
                if (device.bondState == BluetoothDevice.BOND_NONE) {
                    return TrezorTransportWriteResult(success = false, error = "Bonding failed or rejected")
                }
            }
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                return TrezorTransportWriteResult(success = false, error = "Bonding timeout")
            }
            Logger.info("Device bonded successfully: '$address'", context = TAG)
        } else if (device.bondState == BluetoothDevice.BOND_BONDING) {
            Logger.info("Device is currently bonding, waiting: '$address'", context = TAG)
            var bondAttempts = 0
            while (device.bondState == BluetoothDevice.BOND_BONDING && bondAttempts < MAX_BOND_POLL_ATTEMPTS) {
                Thread.sleep(BOND_POLL_INTERVAL_MS)
                bondAttempts++
            }
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                return TrezorTransportWriteResult(success = false, error = "Bonding failed")
            }
        } else {
            Logger.info("Device already bonded: '$address'", context = TAG)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private fun openBleDevice(path: String): TrezorTransportWriteResult {
        val address = path.removePrefix("ble:")
        // Prefer a handle from a recent scan, but fall back to resolving the
        // address directly so we can reconnect to a known device without a
        // fresh scan — a scan right after a disconnect often finds nothing yet.
        val device = discoveredBleDevices[address]
            ?: runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull()
            ?: return TrezorTransportWriteResult(success = false, error = "Device not found: $path")

        closeBleDevice(path)

        val bondError = waitForBonding(device, address)
        if (bondError != null) return bondError

        val connectionLatch = CountDownLatch(1)
        val gatt = device.connectGatt(context, false, bleGattCallback)

        val connection = BleConnection(
            gatt = gatt,
            readCharacteristic = null,
            writeCharacteristic = null,
            connectionLatch = connectionLatch
        )

        bleConnections[path] = connection

        if (!connectionLatch.await(CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            closeBleDevice(path)
            return TrezorTransportWriteResult(success = false, error = "Connection timeout")
        }

        val updatedConnection = bleConnections[path]
        if (updatedConnection == null || !updatedConnection.isConnected) {
            closeBleDevice(path)
            return TrezorTransportWriteResult(success = false, error = "Failed to connect")
        }

        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)

        val staleCount = updatedConnection.readQueue.size
        if (staleCount > 0) {
            updatedConnection.readQueue.clear()
            TrezorDebugLog.log("OPEN", "Drained $staleCount stale notifications from read queue")
        }

        // Stabilization delay: device THP layer needs time after BLE reconnect
        Thread.sleep(BLE_CONNECTION_STABILIZATION_MS)

        Logger.info("BLE device opened: '$path'", context = TAG)
        return TrezorTransportWriteResult(success = true, error = "")
    }

    @Suppress("TooGenericExceptionCaught")
    @SuppressLint("MissingPermission")
    private fun closeBleDevice(path: String): TrezorTransportWriteResult {
        val connection = bleConnections[path]
            ?: return TrezorTransportWriteResult(success = true, error = "")

        userInitiatedCloseSet.add(path)
        return try {
            val disconnectLatch = CountDownLatch(1)
            bleConnections[path] = connection.copy(disconnectLatch = disconnectLatch)

            connection.gatt.disconnect()

            val disconnected = disconnectLatch.await(DISCONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val timeoutError = if (disconnected) null else "BLE disconnect timed out; forced close"
            if (!disconnected) {
                Logger.warn("BLE disconnect timeout, forcing close: '$path'", context = TAG)
            }

            bleConnections.remove(path)
            connection.gatt.close()
            Thread.sleep(100)
            Logger.info("BLE device closed: '$path'", context = TAG)
            TrezorTransportWriteResult(success = timeoutError == null, error = timeoutError.orEmpty())
        } catch (e: Exception) {
            Logger.error("BLE close failed", e, context = TAG)
            TrezorTransportWriteResult(success = false, error = e.message ?: "BLE close failed")
        } finally {
            userInitiatedCloseSet.remove(path)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readBleChunk(path: String): TrezorTransportReadResult {
        val connection = bleConnections[path]
            ?: return TrezorTransportReadResult(
                success = false,
                data = byteArrayOf(),
                error = "Device not open: $path"
            )

        return try {
            val data = connection.readQueue.poll(BLE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: return TrezorTransportReadResult(
                    success = false,
                    data = byteArrayOf(),
                    error = "Read timeout"
                )

            Logger.debug("BLE read ${data.size} bytes from '$path'", context = TAG)
            TrezorTransportReadResult(success = true, data = data, error = "")
        } catch (e: Exception) {
            Logger.error("BLE read failed", e, context = TAG)
            TrezorTransportReadResult(success = false, data = byteArrayOf(), error = e.message ?: "Read failed")
        }
    }

    @Suppress(
        "TooGenericExceptionCaught",
        "CyclomaticComplexMethod",
        "LongMethod",
        "NestedBlockDepth",
        "ReturnCount",
        "LoopWithTooManyJumpStatements",
    )
    @SuppressLint("MissingPermission")
    private fun writeBleChunk(path: String, data: ByteArray): TrezorTransportWriteResult {
        val connection = bleConnections[path]
            ?: return TrezorTransportWriteResult(success = false, error = "Device not open: $path")

        val writeChar = connection.writeCharacteristic
            ?: return TrezorTransportWriteResult(success = false, error = "Write characteristic not available")

        if (!connection.isConnected) {
            Logger.warn("BLE write attempted on disconnected device: '$path'", context = TAG)
            return TrezorTransportWriteResult(success = false, error = "Device disconnected")
        }

        return try {
            var lastError = "Write initiation failed"
            for (attempt in 1..BLE_WRITE_RETRY_COUNT) {
                val writeLatch = CountDownLatch(1)
                connection.writeLatch = writeLatch
                connection.writeStatus = BluetoothGatt.GATT_SUCCESS

                @Suppress("DEPRECATION")
                writeChar.value = data
                @Suppress("DEPRECATION")
                val success = connection.gatt.writeCharacteristic(writeChar)

                if (!success) {
                    val connState = connection.isConnected
                    val charPropsHex = Integer.toHexString(writeChar.properties)
                    Logger.warn(
                        "BLE write initiation failed (attempt '$attempt'/'$BLE_WRITE_RETRY_COUNT'): '$path', " +
                            "isConnected='$connState', charProps='0x$charPropsHex', dataLen='${data.size}'",
                        context = TAG,
                    )
                    if (attempt < BLE_WRITE_RETRY_COUNT) {
                        Thread.sleep(BLE_WRITE_RETRY_DELAY_MS)
                        continue
                    }
                    return TrezorTransportWriteResult(success = false, error = lastError)
                }

                if (!writeLatch.await(WRITE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)) {
                    lastError = "Write timeout"
                    Logger.warn(
                        "BLE write timeout (attempt '$attempt'/'$BLE_WRITE_RETRY_COUNT'): '$path'",
                        context = TAG,
                    )
                    if (attempt < BLE_WRITE_RETRY_COUNT) {
                        Thread.sleep(BLE_WRITE_RETRY_DELAY_MS)
                        continue
                    }
                    return TrezorTransportWriteResult(success = false, error = lastError)
                }

                if (connection.writeStatus != BluetoothGatt.GATT_SUCCESS) {
                    lastError = "Write callback failed: ${connection.writeStatus}"
                    Logger.warn(
                        "BLE write callback failed with status '${connection.writeStatus}' " +
                            "(attempt '$attempt'/'$BLE_WRITE_RETRY_COUNT'): '$path'",
                        context = TAG,
                    )
                    if (attempt < BLE_WRITE_RETRY_COUNT) {
                        Thread.sleep(BLE_WRITE_RETRY_DELAY_MS)
                        continue
                    }
                    return TrezorTransportWriteResult(success = false, error = lastError)
                }

                Logger.debug("BLE wrote '${data.size}' bytes to '$path' (attempt '$attempt')", context = TAG)

                // Small delay between writes to avoid overwhelming the GATT
                Thread.sleep(BLE_WRITE_INTER_DELAY_MS)

                return TrezorTransportWriteResult(success = true, error = "")
            }

            TrezorTransportWriteResult(success = false, error = lastError)
        } catch (e: Exception) {
            Logger.error("BLE write failed", e, context = TAG)
            TrezorTransportWriteResult(success = false, error = e.message ?: "Write failed")
        }
    }

    @SuppressLint("MissingPermission")
    private val bleGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val path = "ble:${gatt.device.address}"
            val connection = bleConnections[path]

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.warn("BLE connection state changed with status '$status' for '$path'", context = TAG)
                connection?.isConnected = false
                connection?.connectionLatch?.countDown()
                connection?.disconnectLatch?.countDown()
                if (!userInitiatedCloseSet.remove(path)) {
                    _externalDisconnect.tryEmit(path)
                }
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Logger.debug("BLE connected, requesting MTU: '$path'", context = TAG)
                    val mtuResult = gatt.requestMtu(256)
                    if (!mtuResult) {
                        Logger.warn("MTU request failed, proceeding with service discovery: '$path'", context = TAG)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Logger.debug("BLE disconnected: '$path'", context = TAG)
                    connection?.isConnected = false
                    connection?.connectionLatch?.countDown()
                    connection?.disconnectLatch?.countDown()
                    if (!userInitiatedCloseSet.remove(path)) {
                        _externalDisconnect.tryEmit(path)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val path = "ble:${gatt.device.address}"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Logger.info("MTU changed to '$mtu' for '$path'", context = TAG)
            } else {
                Logger.warn("MTU change failed with status '$status' for '$path'", context = TAG)
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val path = "ble:${gatt.device.address}"
            val connection = bleConnections[path] ?: return

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.error("Service discovery failed: '$status'", context = TAG)
                connection.connectionLatch?.countDown()
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Logger.error("Trezor service not found", context = TAG)
                connection.connectionLatch?.countDown()
                return
            }

            val writeChar = service.getCharacteristic(WRITE_CHAR_UUID)
            val notifyChar = service.getCharacteristic(NOTIFY_CHAR_UUID)

            if (writeChar == null || notifyChar == null) {
                Logger.error("Required characteristics not found", context = TAG)
                connection.connectionLatch?.countDown()
                return
            }

            // Use WRITE_TYPE_DEFAULT (with response) for more reliable writes
            // Some Trezor devices don't handle NO_RESPONSE well
            @Suppress("DEPRECATION")
            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            gatt.setCharacteristicNotification(notifyChar, true)

            val pushChar = service.getCharacteristic(PUSH_CHAR_UUID)
            if (pushChar != null) {
                gatt.setCharacteristicNotification(pushChar, true)
            }

            connection.readCharacteristic = notifyChar
            connection.writeCharacteristic = writeChar
            connection.isConnected = false

            // Enable notifications via CCCD descriptor for TX characteristic
            val descriptor = notifyChar.getDescriptor(CCCD_UUID)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                val writeResult = gatt.writeDescriptor(descriptor)
                if (!writeResult) {
                    Logger.warn("CCCD descriptor write failed to initiate: '$path'", context = TAG)
                    // Also enable CCCD for PUSH characteristic before signaling ready
                    enablePushCccd(gatt, pushChar, path)
                    connection.isConnected = true
                    connection.connectionLatch?.countDown()
                }
            } else {
                Logger.warn("CCCD descriptor not found, proceeding: '$path'", context = TAG)
                enablePushCccd(gatt, pushChar, path)
                connection.isConnected = true
                connection.connectionLatch?.countDown()
            }

            Logger.info("BLE services discovered: '$path'", context = TAG)
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val path = "ble:${gatt.device.address}"
            val connection = bleConnections[path] ?: return

            // Only process notifications from the NOTIFY characteristic
            if (characteristic.uuid != NOTIFY_CHAR_UUID) {
                Logger.debug("Ignoring notification from non-TX char: '${characteristic.uuid}'", context = TAG)
                return
            }

            @Suppress("DEPRECATION")
            val data = characteristic.value

            if (data != null && data.isNotEmpty()) {
                connection.readQueue.offer(data)
                Logger.debug("BLE TX notification: '${data.size}' bytes", context = TAG)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val path = "ble:${gatt.device.address}"
            val connection = bleConnections[path] ?: return
            connection.writeStatus = status
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.warn("BLE write callback status: '$status' for '$path'", context = TAG)
            }
            connection.writeLatch?.countDown()
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int,
        ) {
            val path = "ble:${gatt.device.address}"
            val connection = bleConnections[path] ?: return

            val charUuid = descriptor.characteristic.uuid
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Logger.info(
                    "CCCD descriptor write complete for '$charUuid': '$path'",
                    context = TAG,
                )
            } else {
                Logger.warn(
                    "CCCD descriptor write failed with status '$status' for '$charUuid': '$path'",
                    context = TAG,
                )
            }

            // Delay subsequent GATT operations without blocking the callback thread
            Handler(Looper.getMainLooper()).postDelayed({
                // If this was the TX characteristic CCCD, also enable PUSH CCCD
                if (descriptor.characteristic.uuid == NOTIFY_CHAR_UUID) {
                    val pushChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(PUSH_CHAR_UUID)
                    if (!enablePushCccd(gatt, pushChar, path)) {
                        // PUSH CCCD not available or failed, signal ready now
                        connection.isConnected = true
                        connection.connectionLatch?.countDown()
                    }
                    // If enablePushCccd returned true, onDescriptorWrite will fire again for PUSH
                } else {
                    // This was the PUSH CCCD write (or other), signal connection ready
                    connection.isConnected = true
                    connection.connectionLatch?.countDown()
                }
            }, BLE_CCCD_STABILIZATION_MS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enablePushCccd(
        gatt: BluetoothGatt,
        pushChar: BluetoothGattCharacteristic?,
        path: String,
    ): Boolean {
        if (pushChar == null) return false
        val pushDescriptor = pushChar.getDescriptor(CCCD_UUID) ?: return false
        @Suppress("DEPRECATION")
        pushDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        @Suppress("DEPRECATION")
        val result = gatt.writeDescriptor(pushDescriptor)
        if (!result) {
            Logger.warn("PUSH CCCD descriptor write failed to initiate: '$path'", context = TAG)
        }
        return result
    }

    private fun isBleDevice(path: String): Boolean = path.startsWith("ble:")

    private fun isTrezorDevice(device: UsbDevice): Boolean {
        return device.vendorId == TREZOR_VENDOR_ID_1 || device.vendorId == TREZOR_VENDOR_ID_2
    }

    fun hasUsbPermission(devicePath: String): Boolean {
        val device = usbManager.deviceList[devicePath] ?: return false
        return usbManager.hasPermission(device)
    }

    fun getUsbDevice(devicePath: String): UsbDevice? {
        return usbManager.deviceList[devicePath]
    }

    fun closeAllConnections() {
        usbConnections.keys.toList().forEach { path -> closeUsbDevice(path) }
        bleConnections.keys.toList().forEach { path -> closeBleDevice(path) }
    }
}
