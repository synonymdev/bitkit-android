package to.bitkit.services

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.ParcelUuid
import com.synonym.bitkitcore.JadeNativeDevice
import com.synonym.bitkitcore.JadeTransportCallback
import com.synonym.bitkitcore.JadeTransportErrorCode
import com.synonym.bitkitcore.JadeTransportKind
import com.synonym.bitkitcore.JadeTransportReadResult
import com.synonym.bitkitcore.JadeTransportResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import to.bitkit.ext.bluetoothManager
import to.bitkit.ext.usbManager
import to.bitkit.models.HwWalletVendor
import to.bitkit.models.TransportType
import to.bitkit.ui.utils.HwUsbId
import to.bitkit.ui.utils.hwUsbId
import to.bitkit.utils.Logger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Byte pipe between bitkit-core's Jade protocol and the phone's radios. Rust owns the CBOR
 * protocol, the pinserver exchange and every deadline; this class only moves bytes over USB
 * serial (a CP210x bridge on Jade v1, native USB CDC on Jade Plus) and Bluetooth (Nordic UART
 * Service). Every callback runs on a Rust blocking thread, so blocking here is expected.
 */
@Suppress("LargeClass", "TooManyFunctions")
@Singleton
class JadeTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : JadeTransportCallback {

    companion object {
        private const val TAG = "JadeTransport"
        private const val ACTION_USB_PERMISSION = "to.bitkit.JADE_USB_PERMISSION"
        private const val BLE_PATH_PREFIX = "ble:"

        /** jade-client-rs `MAX_CHUNK_BYTES`; serial has no MTU so the crate's own serial transport uses it too. */
        const val MAX_CHUNK_SIZE = 509
        private const val DEFAULT_ATT_MTU = 23
        private const val ATT_HEADER_BYTES = 3
        private const val REQUESTED_ATT_MTU = 517

        private const val USB_PERMISSION_TIMEOUT_MS = 60_000L
        private const val USB_CONTROL_TIMEOUT_MS = 1_000
        private const val USB_WRITE_TIMEOUT_MS = 5_000
        private const val USB_READ_TIMEOUT_MAX_MS = 1_000

        /** Silicon Labs CP210x vendor requests (AN571); bmRequestType host-to-device, vendor, interface. */
        private const val CP210X_REQUEST_TYPE_OUT = 0x41
        private const val CP210X_IFC_ENABLE = 0x00
        private const val CP210X_SET_LINE_CTL = 0x03
        private const val CP210X_SET_MHS = 0x07
        private const val CP210X_PURGE = 0x12
        private const val CP210X_SET_BAUDRATE = 0x1E
        private const val CP210X_UART_ENABLE = 0x0001

        /** wValue bits 8-15 data bits (8), bits 4-7 parity (none), bits 0-3 stop bits (1). */
        private const val CP210X_LINE_CTL_8N1 = 0x0800

        /**
         * wValue low byte holds the DTR (bit 0) and RTS (bit 1) states, the high byte which of the two
         * the write applies to. Both lines always change together in one transfer: the ESP32 auto-program
         * circuit only resets or boot-modes the chip while the two differ.
         */
        private const val CP210X_MHS_LINES_ON_OPEN = 0x0303
        private const val CP210X_MHS_LINES_ON_CLOSE = 0x0300
        private const val CP210X_PURGE_ALL = 0x000F

        /** 115200 baud as a 32-bit little-endian value. */
        private val CP210X_BAUDRATE_115200 = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00)

        /** USB CDC PSTN class requests; bmRequestType host-to-device, class, interface. */
        private const val CDC_REQUEST_TYPE_OUT = 0x21
        private const val CDC_SET_LINE_CODING = 0x20
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22

        /** dwDTERate 115200 LE, bCharFormat 0 (one stop bit), bParityType 0 (none), bDataBits 8. */
        private val CDC_LINE_CODING_115200_8N1 = byteArrayOf(0x00, 0xC2.toByte(), 0x01, 0x00, 0x00, 0x00, 0x08)
        private const val CDC_LINE_STATE_ON_OPEN = 0x0003
        private const val CDC_LINE_STATE_ON_CLOSE = 0x0000

        private val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_WRITE_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_NOTIFY_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val BLE_SCAN_MIN_MS = 500L
        private const val BLE_SCAN_MAX_MS = 15_000L
        private const val BLE_CONNECTION_TIMEOUT_MS = 15_000L

        /**
         * A write to an encrypted characteristic waits while the phone (re)pairs with the Jade, which
         * needs a passkey confirmation on the device and gives up after 30 s, so the budget covers that.
         */
        private const val BLE_WRITE_TIMEOUT_MS = 35_000L

        private const val STALE_BOND_ERROR =
            "Bluetooth pairing is no longer valid: forget the Jade in Bluetooth settings and pair it again"
        private const val BLE_WRITE_BUSY_RETRY_DELAY_MS = 50L
        private const val BLE_DISCONNECT_TIMEOUT_MS = 3_000L
        private const val BOND_POLL_INTERVAL_MS = 500L

        /** 60 s: the passkey has to be confirmed on the Jade and in the Android pairing dialog. */
        private const val MAX_BOND_POLL_ATTEMPTS = 120

        private fun ok() = JadeTransportResult(success = true, error = "", errorCode = null)

        private fun fail(error: String, code: JadeTransportErrorCode?) =
            JadeTransportResult(success = false, error = error, errorCode = code)

        private fun readOk(data: ByteArray) =
            JadeTransportReadResult(success = true, data = data, error = "", errorCode = null)

        private fun readFail(error: String, code: JadeTransportErrorCode?) =
            JadeTransportReadResult(success = false, data = byteArrayOf(), error = error, errorCode = code)

        internal fun chunkSizeForMtu(mtu: Int): UInt = (mtu - ATT_HEADER_BYTES).coerceIn(1, MAX_CHUNK_SIZE).toUInt()
    }

    private val usbManager: UsbManager by lazy { context.usbManager }
    private val bluetoothManager: BluetoothManager by lazy { context.bluetoothManager }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }

    private val usbPermissionRequester by lazy {
        UsbPermissionRequester(
            context = context,
            usbManager = usbManager,
            action = ACTION_USB_PERMISSION,
            timeoutMs = USB_PERMISSION_TIMEOUT_MS,
        )
    }

    private val usbConnections = ConcurrentHashMap<String, UsbOpenDevice>()
    private val bleConnections = ConcurrentHashMap<String, BleConnection>()
    private val discoveredBleDevices = ConcurrentHashMap<String, BluetoothDevice>()
    private val discoveredBleNames = ConcurrentHashMap<String, String>()
    private val userInitiatedCloseSet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val optionScopeMutex = Mutex()

    @Volatile
    private var requestUsbPermissionEnabled = true

    @Volatile
    private var bluetoothScanningEnabled = true

    private val _externalDisconnect = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Paths whose link dropped without the app asking for it: unplug, Bluetooth off, or a GATT drop. */
    val externalDisconnect: SharedFlow<String> = _externalDisconnect

    private val _transportRestored = MutableSharedFlow<TransportType>(extraBufferCapacity = 1)

    /** Emits the transport that became available again: Bluetooth back on or a Jade plugged in. */
    val transportRestored: SharedFlow<TransportType> = _transportRestored

    private val connectionStateReceiver = ConnectionStateReceiver(
        onBluetoothOff = {
            bleConnections.forEach { (path, connection) ->
                connection.isConnected = false
                connection.writeStatus = BluetoothGatt.GATT_FAILURE
                releasePendingBleOperations(
                    connectionLatch = connection.connectionLatch,
                    writeLatch = connection.writeLatch,
                    disconnectLatch = connection.disconnectLatch,
                )
                emitExternalDisconnect(path)
            }
        },
        onBluetoothOn = { _transportRestored.tryEmit(TransportType.BLUETOOTH) },
        onUsbDetached = { path ->
            // Only flag it: a bulk transfer may be in flight on the Rust thread, and the fd is closed
            // once Rust hands the disconnect back through closeDevice.
            usbConnections[path]?.let {
                it.detached = true
                emitExternalDisconnect(path)
            }
        },
        onUsbAttached = { device ->
            if (isJadeUsbDevice(device)) _transportRestored.tryEmit(TransportType.USB)
        },
    )

    init {
        connectionStateReceiver.register(context)
    }

    private class UsbOpenDevice(
        val connection: UsbDeviceConnection,
        val driver: UsbDriverSelection,
        /** One max-packet: a read that times out then never discards a partially received packet. */
        val readBuffer: ByteArray = ByteArray(driver.readEndpoint.maxPacketSize),
        /** Serialises transfers against close so the fd is never closed under an in-flight URB. */
        val ioLock: Any = Any(),
        @Volatile var detached: Boolean = false,
    )

    @Suppress("LongParameterList")
    private class BleConnection(
        val gatt: BluetoothGatt,
        @Volatile var writeCharacteristic: BluetoothGattCharacteristic? = null,
        /** Notifications in arrival order, untouched: frames are not aligned to notifications. */
        val readQueue: LinkedBlockingQueue<ByteArray> = LinkedBlockingQueue(),
        @Volatile var mtu: Int = DEFAULT_ATT_MTU,
        /** The link itself is up, whether or not the subscription that makes it usable succeeded. */
        @Volatile var linkUp: Boolean = false,
        @Volatile var isConnected: Boolean = false,
        @Volatile var writesCompleted: Int = 0,
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

    fun isJadeUsbDevice(device: UsbDevice): Boolean = device.hwUsbId()?.vendor == HwWalletVendor.BLOCKSTREAM

    fun hasUsbPermission(devicePath: String): Boolean {
        val device = usbManager.deviceList[devicePath] ?: return false
        return usbManager.hasPermission(device)
    }

    /** Whether the transport currently holds an open Bluetooth link; a scan would drop it. */
    fun hasOpenBleConnection(): Boolean = bleConnections.values.any { it.isConnected }

    /** App-initiated teardown; for Jade the same as [closeDevice], which releases the link fully. */
    fun disconnectDevice(path: String): JadeTransportResult = closeDevice(path)

    fun closeAllConnections() {
        usbConnections.keys.toList().forEach { closeUsbDevice(it) }
        bleConnections.keys.toList().forEach { disconnectBleDevice(it) }
    }

    // ------------------------------------------------------------------
    // JadeTransportCallback
    // ------------------------------------------------------------------

    override fun scanDevices(timeoutMs: UInt): List<JadeNativeDevice> {
        val devices = mutableListOf<JadeNativeDevice>()

        runCatching { scanUsbDevices() }
            .onSuccess {
                devices.addAll(it)
                Logger.debug("USB scan found '${it.size}' Jade device(s)", context = TAG)
            }
            .onFailure { Logger.error("USB scan failed", it, context = TAG) }

        if (bluetoothScanningEnabled) {
            runCatching { scanBleDevices(timeoutMs) }
                .onSuccess {
                    devices.addAll(it)
                    Logger.debug("BLE scan found '${it.size}' Jade device(s)", context = TAG)
                }
                .onFailure { Logger.error("BLE scan failed", it, context = TAG) }
        } else {
            Logger.debug("Skipped BLE scan while Bluetooth scanning is disabled", context = TAG)
        }

        Logger.info("Found '${devices.size}' Jade device(s)", context = TAG)
        return devices
    }

    override fun openDevice(path: String): JadeTransportResult =
        if (isBlePath(path)) openBleDevice(path) else openUsbDevice(path)

    override fun closeDevice(path: String): JadeTransportResult =
        if (isBlePath(path)) disconnectBleDevice(path) else closeUsbDevice(path)

    override fun writeChunk(path: String, data: ByteArray): JadeTransportResult =
        if (isBlePath(path)) writeBleChunk(path, data) else writeUsbChunk(path, data)

    override fun readChunk(path: String, timeoutMs: UInt): JadeTransportReadResult =
        if (isBlePath(path)) readBleChunk(path, timeoutMs) else readUsbChunk(path, timeoutMs)

    override fun getChunkSize(path: String): UInt = when {
        isBlePath(path) -> chunkSizeForMtu(bleConnections[path]?.mtu ?: DEFAULT_ATT_MTU)
        else -> MAX_CHUNK_SIZE.toUInt()
    }

    // ------------------------------------------------------------------
    // USB serial
    // ------------------------------------------------------------------

    private fun scanUsbDevices(): List<JadeNativeDevice> = usbManager.deviceList.values
        .filter { isJadeUsbDevice(it) }
        .map { device ->
            JadeNativeDevice(
                path = device.deviceName,
                transport = JadeTransportKind.SERIAL,
                name = runCatching { device.productName }.getOrNull(),
                // Reading the serial number throws without permission on API 29+.
                serialNumber = if (usbManager.hasPermission(device)) {
                    runCatching { device.serialNumber }.getOrNull()
                } else {
                    null
                },
            )
        }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun openUsbDevice(path: String): JadeTransportResult {
        return try {
            closeUsbDevice(path)

            val device = usbManager.deviceList[path]
                ?: return fail("Device not found: '$path'", JadeTransportErrorCode.NOT_CONNECTED)

            if (!usbManager.hasPermission(device)) {
                if (!requestUsbPermissionEnabled) {
                    Logger.info("Skipped USB permission request for '$path'", context = TAG)
                    return fail("USB permission missing for '$path'", JadeTransportErrorCode.PERMISSION_DENIED)
                }
                if (!usbPermissionRequester.request(device)) {
                    return fail("USB permission denied for '$path'", JadeTransportErrorCode.PERMISSION_DENIED)
                }
            }

            val driver = selectUsbDriver(device)
                ?: return fail("Unsupported USB device '$path'", null)

            val connection = usbManager.openDevice(device)
                ?: return fail("Failed to open device: '$path'", null)

            val claimed = listOfNotNull(driver.controlInterface, driver.dataInterface)
                .all { connection.claimInterface(it, true) }
            if (!claimed) {
                releaseUsb(connection, driver)
                return fail("Failed to claim interface", null)
            }

            val initialised = when (driver.kind) {
                UsbDriverKind.CP210X -> initCp210x(connection, driver)
                UsbDriverKind.CDC_ACM -> initCdcAcm(connection, driver)
            }
            if (!initialised) {
                releaseUsb(connection, driver)
                return fail("Failed to configure serial link", null)
            }

            usbConnections[path] = UsbOpenDevice(connection = connection, driver = driver)
            Logger.info("Opened USB device '$path' as '${driver.kind}'", context = TAG)
            ok()
        } catch (e: Exception) {
            Logger.error("USB open failed", e, context = TAG)
            fail(e.message ?: "Unknown error", null)
        }
    }

    private fun initCp210x(connection: UsbDeviceConnection, driver: UsbDriverSelection): Boolean {
        val index = driver.dataInterface.id
        fun control(request: Int, value: Int, data: ByteArray? = null): Int = connection.controlTransfer(
            CP210X_REQUEST_TYPE_OUT,
            request,
            value,
            index,
            data,
            data?.size ?: 0,
            USB_CONTROL_TIMEOUT_MS,
        )
        if (control(CP210X_IFC_ENABLE, CP210X_UART_ENABLE) < 0) {
            Logger.error("CP210x interface enable failed", context = TAG)
            return false
        }
        if (control(CP210X_SET_BAUDRATE, 0, CP210X_BAUDRATE_115200) < 0) {
            Logger.error("CP210x baud rate setup failed", context = TAG)
            return false
        }
        if (control(CP210X_SET_LINE_CTL, CP210X_LINE_CTL_8N1) < 0) {
            Logger.error("CP210x line control setup failed", context = TAG)
            return false
        }
        if (control(CP210X_SET_MHS, CP210X_MHS_LINES_ON_OPEN) < 0) {
            Logger.warn("CP210x modem line setup failed", context = TAG)
        }
        if (control(CP210X_PURGE, CP210X_PURGE_ALL) < 0) {
            Logger.warn("CP210x purge failed", context = TAG)
        }
        return true
    }

    private fun initCdcAcm(connection: UsbDeviceConnection, driver: UsbDriverSelection): Boolean {
        val index = driver.controlInterface?.id ?: driver.dataInterface.id
        val lineCoding = connection.controlTransfer(
            CDC_REQUEST_TYPE_OUT,
            CDC_SET_LINE_CODING,
            0,
            index,
            CDC_LINE_CODING_115200_8N1,
            CDC_LINE_CODING_115200_8N1.size,
            USB_CONTROL_TIMEOUT_MS,
        )
        if (lineCoding < 0) {
            // Native USB ignores the baud rate; the request is only advisory.
            Logger.warn("CDC line coding setup failed", context = TAG)
        }
        val lineState = connection.controlTransfer(
            CDC_REQUEST_TYPE_OUT,
            CDC_SET_CONTROL_LINE_STATE,
            CDC_LINE_STATE_ON_OPEN,
            index,
            null,
            0,
            USB_CONTROL_TIMEOUT_MS,
        )
        if (lineState < 0) {
            Logger.warn("CDC control line setup failed", context = TAG)
        }
        return true
    }

    private fun setModemLinesOnClose(device: UsbOpenDevice) {
        when (device.driver.kind) {
            UsbDriverKind.CP210X -> device.connection.controlTransfer(
                CP210X_REQUEST_TYPE_OUT,
                CP210X_SET_MHS,
                CP210X_MHS_LINES_ON_CLOSE,
                device.driver.dataInterface.id,
                null,
                0,
                USB_CONTROL_TIMEOUT_MS,
            )
            UsbDriverKind.CDC_ACM -> device.connection.controlTransfer(
                CDC_REQUEST_TYPE_OUT,
                CDC_SET_CONTROL_LINE_STATE,
                CDC_LINE_STATE_ON_CLOSE,
                device.driver.controlInterface?.id ?: device.driver.dataInterface.id,
                null,
                0,
                USB_CONTROL_TIMEOUT_MS,
            )
        }
    }

    private fun releaseUsb(connection: UsbDeviceConnection, driver: UsbDriverSelection) {
        driver.controlInterface?.let { connection.releaseInterface(it) }
        connection.releaseInterface(driver.dataInterface)
        connection.close()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun closeUsbDevice(path: String): JadeTransportResult {
        val device = usbConnections.remove(path) ?: return ok()
        return try {
            synchronized(device.ioLock) {
                if (!device.detached) {
                    runCatching { setModemLinesOnClose(device) }
                        .onFailure { Logger.warn("Failed to clear modem lines for '$path'", it, context = TAG) }
                }
                releaseUsb(device.connection, device.driver)
            }
            Logger.info("Closed USB device '$path'", context = TAG)
            ok()
        } catch (e: Exception) {
            Logger.error("USB close failed", e, context = TAG)
            fail(e.message ?: "Unknown error", null)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readUsbChunk(path: String, timeoutMs: UInt): JadeTransportReadResult {
        val device = usbConnections[path]
            ?: return readFail("Device not open: '$path'", JadeTransportErrorCode.NOT_CONNECTED)
        if (device.detached) return readFail("USB device detached: '$path'", JadeTransportErrorCode.DISCONNECTED)

        return try {
            val timeout = timeoutMs.toLong().coerceIn(1L, USB_READ_TIMEOUT_MAX_MS.toLong()).toInt()
            // Synchronous on purpose: the async UsbRequest API needs its URB cancelled and reaped
            // before close, or the kernel later writes into freed memory (native SIGSEGV).
            val read = synchronized(device.ioLock) {
                device.connection.bulkTransfer(
                    device.driver.readEndpoint,
                    device.readBuffer,
                    device.readBuffer.size,
                    timeout,
                )
            }
            when {
                read > 0 -> readOk(device.readBuffer.copyOf(read))
                read == 0 -> readOk(byteArrayOf())
                device.detached || usbManager.deviceList[path] == null ->
                    readFail("USB device detached: '$path'", JadeTransportErrorCode.DISCONNECTED)
                // A timeout with nothing received is the normal state while the user reads the device.
                else -> readOk(byteArrayOf())
            }
        } catch (e: Exception) {
            Logger.error("USB read failed", e, context = TAG)
            readFail(e.message ?: "Unknown error", null)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeUsbChunk(path: String, data: ByteArray): JadeTransportResult {
        val device = usbConnections[path]
            ?: return fail("Device not open: '$path'", JadeTransportErrorCode.NOT_CONNECTED)
        if (device.detached) return fail("USB device detached: '$path'", JadeTransportErrorCode.DISCONNECTED)

        return try {
            val written = synchronized(device.ioLock) {
                device.connection.bulkTransfer(device.driver.writeEndpoint, data, data.size, USB_WRITE_TIMEOUT_MS)
            }
            if (written != data.size) {
                val code = if (device.detached) JadeTransportErrorCode.DISCONNECTED else JadeTransportErrorCode.TIMEOUT
                return fail("USB write wrote '$written' of '${data.size}' bytes", code)
            }
            Logger.debug("USB wrote '${data.size}' bytes to '$path'", context = TAG)
            ok()
        } catch (e: Exception) {
            Logger.error("USB write failed", e, context = TAG)
            fail(e.message ?: "Unknown error", null)
        }
    }

    // ------------------------------------------------------------------
    // Bluetooth (Nordic UART Service)
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun scanBleDevices(timeoutMs: UInt): List<JadeNativeDevice> {
        if (bluetoothAdapter?.isEnabled != true) {
            Logger.warn("Bluetooth is not enabled", context = TAG)
            return emptyList()
        }
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return emptyList()

        discoveredBleDevices.clear()
        discoveredBleNames.clear()

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
            .build()
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(scanFilter), scanSettings, bleScanCallback)
        Logger.debug("BLE scan started", context = TAG)
        Thread.sleep(timeoutMs.toLong().coerceIn(BLE_SCAN_MIN_MS, BLE_SCAN_MAX_MS))
        scanner.stopScan(bleScanCallback)
        Logger.debug("BLE scan stopped", context = TAG)

        return discoveredBleDevices.values.map { device ->
            JadeNativeDevice(
                path = blePath(device.address),
                transport = JadeTransportKind.BLUETOOTH,
                name = discoveredBleNames[device.address] ?: device.name ?: "Jade",
                serialNumber = null,
            )
        }
    }

    @SuppressLint("MissingPermission")
    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address
            if (discoveredBleDevices.putIfAbsent(address, device) == null) {
                val name = result.scanRecord?.deviceName ?: device.name
                name?.let { discoveredBleNames[address] = it }
                Logger.debug("BLE device found: '$address' ('$name')", context = TAG)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.warn("BLE scan failed: '$errorCode'", context = TAG)
        }
    }

    @Suppress("ReturnCount")
    @SuppressLint("MissingPermission")
    private fun waitForBonding(device: BluetoothDevice, address: String): JadeTransportResult? {
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                Logger.info("Device already bonded: '$address'", context = TAG)
                return null
            }
            BluetoothDevice.BOND_NONE -> {
                Logger.info("Device not bonded, initiating bonding: '$address'", context = TAG)
                if (!device.createBond()) return fail("Failed to initiate bonding", null)
            }
            else -> Logger.info("Device is currently bonding, waiting: '$address'", context = TAG)
        }
        var attempts = 0
        while (device.bondState != BluetoothDevice.BOND_BONDED && attempts < MAX_BOND_POLL_ATTEMPTS) {
            Thread.sleep(BOND_POLL_INTERVAL_MS)
            attempts++
            if (device.bondState == BluetoothDevice.BOND_NONE) return fail("Bonding failed or rejected", null)
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            return fail("Bonding timeout", JadeTransportErrorCode.TIMEOUT)
        }
        Logger.info("Device bonded successfully: '$address'", context = TAG)
        return null
    }

    @Suppress("ReturnCount")
    @SuppressLint("MissingPermission")
    private fun openBleDevice(path: String): JadeTransportResult {
        bleConnections[path]?.takeIf { it.isConnected && it.writeCharacteristic != null }?.let {
            it.readQueue.clear()
            Logger.info("Reused open BLE device '$path'", context = TAG)
            return ok()
        }

        val address = path.removePrefix(BLE_PATH_PREFIX)
        // A scan right after a disconnect often finds nothing yet, so resolve the address directly.
        val device = discoveredBleDevices[address]
            ?: runCatching { bluetoothAdapter?.getRemoteDevice(address) }.getOrNull()
            ?: return fail("Device not found: '$path'", JadeTransportErrorCode.NOT_CONNECTED)

        bleConnections[path]?.takeIf { !it.isConnected }?.let { disconnectBleDevice(path) }

        waitForBonding(device, address)?.let { return it }

        val connectionLatch = CountDownLatch(1)
        val gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        bleConnections[path] = BleConnection(gatt = gatt, connectionLatch = connectionLatch)

        if (!connectionLatch.await(BLE_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            disconnectBleDevice(path)
            return fail("BLE connection timeout", JadeTransportErrorCode.TIMEOUT)
        }
        val connection = bleConnections[path]
        if (connection == null || !connection.isConnected) {
            disconnectBleDevice(path)
            return fail("Failed to connect", null)
        }

        // A 30 KB PSBT is dozens of write-with-response round trips, each of which has to land well
        // inside the firmware's two second inter-chunk window.
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        connection.readQueue.clear()
        Logger.info("Opened BLE device '$path' with MTU '${connection.mtu}'", context = TAG)
        return ok()
    }

    @Suppress("TooGenericExceptionCaught")
    @SuppressLint("MissingPermission")
    private fun disconnectBleDevice(path: String): JadeTransportResult {
        val connection = bleConnections[path] ?: return ok()
        userInitiatedCloseSet.add(path)
        return try {
            // Disconnect whenever the link came up, even if setup failed afterwards: closing the
            // client alone can leave the Jade's single connection slot occupied until it reboots.
            if (connection.linkUp) {
                val disconnectLatch = CountDownLatch(1)
                connection.disconnectLatch = disconnectLatch
                connection.gatt.disconnect()
                if (!disconnectLatch.await(BLE_DISCONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    Logger.warn("BLE disconnect timeout, forcing close: '$path'", context = TAG)
                }
            }
            bleConnections.remove(path)
            connection.isConnected = false
            connection.gatt.close()
            connection.readQueue.clear()
            Logger.info("Closed BLE device '$path'", context = TAG)
            ok()
        } catch (e: Exception) {
            Logger.error("BLE close failed", e, context = TAG)
            fail(e.message ?: "BLE close failed", null)
        } finally {
            userInitiatedCloseSet.remove(path)
        }
    }

    private fun readBleChunk(path: String, timeoutMs: UInt): JadeTransportReadResult {
        val connection = bleConnections[path]
            ?: return readFail("Device not open: '$path'", JadeTransportErrorCode.NOT_CONNECTED)
        if (!connection.isConnected) return readFail("BLE disconnected: '$path'", JadeTransportErrorCode.DISCONNECTED)

        val data = connection.readQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        return when {
            data != null -> readOk(data)
            !connection.isConnected -> readFail("BLE disconnected: '$path'", JadeTransportErrorCode.DISCONNECTED)
            else -> readOk(byteArrayOf())
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    @SuppressLint("MissingPermission")
    private fun writeBleChunk(path: String, data: ByteArray): JadeTransportResult {
        val connection = bleConnections[path]
            ?: return fail("Device not open: '$path'", JadeTransportErrorCode.NOT_CONNECTED)
        val writeChar = connection.writeCharacteristic
            ?: return fail("Write characteristic not available", JadeTransportErrorCode.NOT_CONNECTED)
        if (!connection.isConnected) return fail("BLE disconnected: '$path'", JadeTransportErrorCode.DISCONNECTED)

        return try {
            val writeLatch = CountDownLatch(1)
            connection.writeLatch = writeLatch
            connection.writeStatus = BluetoothGatt.GATT_FAILURE

            var started = startCharacteristicWrite(connection.gatt, writeChar, data)
            if (!started) {
                // The GATT stack is still busy with the previous acknowledgement; one short retry.
                Thread.sleep(BLE_WRITE_BUSY_RETRY_DELAY_MS)
                started = startCharacteristicWrite(connection.gatt, writeChar, data)
            }
            if (!started) return fail("BLE write initiation failed", null)

            if (!writeLatch.await(BLE_WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // The very first write stalling on a bonded device means the Jade rejected the stored
                // key and the phone's re-pairing was not confirmed: only a fresh bond fixes that.
                val bonded = connection.gatt.device.bondState == BluetoothDevice.BOND_BONDED
                if (connection.writesCompleted == 0 && bonded) {
                    Logger.warn("BLE write stalled on a bonded Jade; the bond is stale: '$path'", context = TAG)
                    return fail(STALE_BOND_ERROR, null)
                }
                return fail("BLE write timeout", JadeTransportErrorCode.TIMEOUT)
            }
            if (connection.writeStatus != BluetoothGatt.GATT_SUCCESS) {
                val code = if (connection.isConnected) null else JadeTransportErrorCode.DISCONNECTED
                return fail("BLE write failed with status '${connection.writeStatus}'", code)
            }
            connection.writesCompleted++
            Logger.debug("BLE wrote '${data.size}' bytes to '$path'", context = TAG)
            ok()
        } catch (e: Exception) {
            Logger.error("BLE write failed", e, context = TAG)
            fail(e.message ?: "Write failed", null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCharacteristicWrite(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        data: ByteArray,
    ): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeCharacteristic(
            characteristic,
            data,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
        ) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            gatt.writeCharacteristic(characteristic)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, notifyChar: BluetoothGattCharacteristic): Boolean {
        if (!gatt.setCharacteristicNotification(notifyChar, true)) return false
        val descriptor = notifyChar.getDescriptor(CCCD_UUID) ?: return false
        // The firmware answers "request not supported" to a subscription of the wrong kind, so ask
        // for whichever of the two the characteristic offers, preferring notifications.
        val supportsNotify = notifyChar.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val value = if (supportsNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        Logger.debug(
            "Subscribing to Jade TX with properties '0x${Integer.toHexString(notifyChar.properties)}'",
            context = TAG,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = value
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun onNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != NUS_NOTIFY_CHAR_UUID || value.isEmpty()) return
        bleConnections[blePath(gatt.device.address)]?.readQueue?.offer(value.copyOf())
    }

    private fun finishConnect(connection: BleConnection, connected: Boolean) {
        connection.isConnected = connected
        connection.connectionLatch?.countDown()
    }

    @SuppressLint("MissingPermission")
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val path = blePath(gatt.device.address)
            val connection = bleConnections[path]
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                Logger.debug("BLE disconnected with status '$status' for '$path'", context = TAG)
                connection?.let {
                    it.linkUp = false
                    it.isConnected = false
                    it.writeStatus = BluetoothGatt.GATT_FAILURE
                    releasePendingBleOperations(it.connectionLatch, it.writeLatch, it.disconnectLatch)
                }
                emitExternalDisconnect(path)
                return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connection?.linkUp = true
                Logger.debug("BLE connected, requesting MTU for '$path'", context = TAG)
                if (!gatt.requestMtu(REQUESTED_ATT_MTU)) gatt.discoverServices()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val path = blePath(gatt.device.address)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                bleConnections[path]?.mtu = mtu
                Logger.info("Negotiated MTU '$mtu' for '$path'", context = TAG)
            } else {
                Logger.warn("MTU negotiation failed with status '$status' for '$path'", context = TAG)
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val path = blePath(gatt.device.address)
            val connection = bleConnections[path] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Logger.error("Service discovery failed with status '$status' for '$path'", context = TAG)
                finishConnect(connection, connected = false)
                return
            }
            val service = gatt.getService(NUS_SERVICE_UUID)
            val writeChar = service?.getCharacteristic(NUS_WRITE_CHAR_UUID)
            val notifyChar = service?.getCharacteristic(NUS_NOTIFY_CHAR_UUID)
            if (writeChar == null || notifyChar == null) {
                Logger.error("Jade UART service not found on '$path'", context = TAG)
                finishConnect(connection, connected = false)
                return
            }
            if (writeChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) {
                // Write-without-response silently drops chunks on the ESP32 GATT stack.
                Logger.error("Jade write characteristic lacks write-with-response on '$path'", context = TAG)
                finishConnect(connection, connected = false)
                return
            }
            connection.writeCharacteristic = writeChar
            if (!enableNotifications(gatt, notifyChar)) {
                Logger.error("Failed to enable notifications on '$path'", context = TAG)
                finishConnect(connection, connected = false)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val path = blePath(gatt.device.address)
            val connection = bleConnections[path] ?: return
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (!success) Logger.warn("CCCD write failed with status '$status' for '$path'", context = TAG)
            finishConnect(connection, connected = success)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotification(gatt, characteristic, value)
        }

        @Deprecated("Replaced on API 33 by the overload carrying the value")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            // API 33 delivers the value through the overload above; this one only serves older releases.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            onNotification(gatt, characteristic, value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            val connection = bleConnections[blePath(gatt.device.address)] ?: return
            connection.writeStatus = status
            connection.writeLatch?.countDown()
        }
    }

    private fun emitExternalDisconnect(path: String) {
        if (!userInitiatedCloseSet.remove(path)) {
            _externalDisconnect.tryEmit(path)
        }
    }

    private fun isBlePath(path: String) = path.startsWith(BLE_PATH_PREFIX)

    private fun blePath(address: String) = "$BLE_PATH_PREFIX$address"
}

internal enum class UsbDriverKind { CP210X, CDC_ACM }

/** The interfaces and bulk endpoints a Jade's USB bridge exposes, chosen from its descriptors. */
internal data class UsbDriverSelection(
    val kind: UsbDriverKind,
    val dataInterface: UsbInterface,
    /** CDC only: the communication-class interface that takes the line requests. */
    val controlInterface: UsbInterface?,
    val readEndpoint: UsbEndpoint,
    val writeEndpoint: UsbEndpoint,
)

private data class BulkEndpoints(val read: UsbEndpoint, val write: UsbEndpoint)

private fun UsbInterface.bulkEndpoints(): BulkEndpoints? {
    var read: UsbEndpoint? = null
    var write: UsbEndpoint? = null
    for (i in 0 until endpointCount) {
        val endpoint = getEndpoint(i)
        if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
        when (endpoint.direction) {
            UsbConstants.USB_DIR_IN -> read = endpoint
            UsbConstants.USB_DIR_OUT -> write = endpoint
        }
    }
    val readEndpoint = read ?: return null
    val writeEndpoint = write ?: return null
    return BulkEndpoints(read = readEndpoint, write = writeEndpoint)
}

/**
 * Picks the driver by interface class rather than product id, so every Espressif layout (native CDC
 * or the ROM serial/JTAG port) resolves to CDC-ACM and only the CP210x bridge takes the vendor path.
 */
internal fun selectUsbDriver(device: UsbDevice): UsbDriverSelection? {
    val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
    interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA }?.let { data ->
        val endpoints = data.bulkEndpoints() ?: return@let
        return UsbDriverSelection(
            kind = UsbDriverKind.CDC_ACM,
            dataInterface = data,
            controlInterface = interfaces.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_COMM },
            readEndpoint = endpoints.read,
            writeEndpoint = endpoints.write,
        )
    }
    val cp210x = device.hwUsbId() == HwUsbId.JADE_CP210X
    if (!cp210x) return null
    val data = interfaces.firstOrNull() ?: return null
    val endpoints = data.bulkEndpoints() ?: return null
    return UsbDriverSelection(
        kind = UsbDriverKind.CP210X,
        dataInterface = data,
        controlInterface = null,
        readEndpoint = endpoints.read,
        writeEndpoint = endpoints.write,
    )
}
