package to.bitkit.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import to.bitkit.ext.connectGattDirect
import to.bitkit.utils.Logger

/**
 * Finds the closest nearby Bitkit wallet that serves a payment request and reads that request.
 *
 * While started it scans for the nearby service. Once an advertiser at or above
 * [NearbyProtocol.MIN_RSSI] is seen, it waits [CANDIDATE_WINDOW_MS] for others, connects to the
 * strongest one and reads the request. A device that fails or was already read is skipped for
 * [RETRY_AFTER_MS]. All callbacks reach [onRequest] on the main thread.
 */
@SuppressLint("MissingPermission") // Callers start it only after the nearby permissions are granted.
class NearbyScanner(
    private val context: Context,
    private val onRequest: (String) -> Unit,
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val candidates = mutableMapOf<String, Pair<BluetoothDevice, Int>>()
    private val skipped = mutableMapOf<String, Long>()
    private var isScanning = false
    private var isStarted = false
    private var session: ReadSession? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { onCandidate(result.device, result.rssi) }
        }

        override fun onScanFailed(errorCode: Int) {
            Logger.warn("Nearby scan failed: $errorCode", context = TAG)
            isScanning = false
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun start() {
        isStarted = true
        startScan()
    }

    fun stop() {
        isStarted = false
        stopScan()
        handler.removeCallbacksAndMessages(null)
        session?.close()
        session = null
        candidates.clear()
    }

    private fun startScan() {
        if (isScanning || session != null || !isStarted) return
        val scanner = bluetoothManager?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(NearbyProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
            .onSuccess { isScanning = true }
            .onFailure { Logger.warn("Failed to start nearby scan", it, context = TAG) }
    }

    private fun stopScan() {
        if (!isScanning) return
        runCatching { bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        isScanning = false
    }

    private fun onCandidate(device: BluetoothDevice, rssi: Int) {
        if (!isStarted || session != null || rssi < NearbyProtocol.MIN_RSSI) return
        val skippedAt = skipped[device.address]
        if (skippedAt != null && System.currentTimeMillis() - skippedAt < RETRY_AFTER_MS) return
        val isFirst = candidates.isEmpty()
        val previous = candidates[device.address]?.second
        if (previous == null || rssi > previous) candidates[device.address] = device to rssi
        if (isFirst) handler.postDelayed(::connectToClosest, CANDIDATE_WINDOW_MS)
    }

    private fun connectToClosest() {
        val closest = candidates.values.maxByOrNull { it.second }?.first ?: return
        candidates.clear()
        if (!isStarted) return
        stopScan()
        session = ReadSession(closest).also { it.open() }
    }

    private fun finish(device: BluetoothDevice, request: String?) {
        skipped[device.address] = System.currentTimeMillis()
        session?.close()
        session = null
        if (!isStarted) return
        if (request != null) {
            onRequest(request)
        } else {
            startScan()
        }
    }

    private inner class ReadSession(private val device: BluetoothDevice) : BluetoothGattCallback() {
        private var gatt: BluetoothGatt? = null
        private var length = 0
        private val chunks = mutableListOf<ByteArray>()
        private var isDone = false
        private val timeout = Runnable { complete(null) }

        fun open() {
            handler.postDelayed(timeout, READ_TIMEOUT_MS)
            gatt = runCatching { device.connectGattDirect(context, this, BluetoothDevice.TRANSPORT_LE) }
                .onFailure { Logger.warn("Failed to connect to nearby device", it, context = TAG) }
                .getOrNull()
            if (gatt == null) complete(null)
        }

        fun close() {
            handler.removeCallbacks(timeout)
            runCatching {
                gatt?.disconnect()
                gatt?.close()
            }
            gatt = null
        }

        private fun complete(request: String?) {
            handler.post {
                if (isDone) return@post
                isDone = true
                finish(device, request)
            }
        }

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                status != BluetoothGatt.GATT_SUCCESS -> complete(null)
                newState == BluetoothProfile.STATE_CONNECTED -> {
                    if (!gatt.requestMtu(MTU)) gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> complete(null)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (!gatt.discoverServices()) complete(null)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || !read(gatt, NearbyProtocol.META_UUID)) complete(null)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            onRead(gatt, characteristic, value, status)
        }

        @Deprecated("Called instead of the variant with a value before API 33")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            @Suppress("DEPRECATION")
            onRead(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        private fun onRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return complete(null)
            if (characteristic.uuid == NearbyProtocol.META_UUID) {
                length = NearbyProtocol.requestLength(value) ?: return complete(null)
            } else {
                chunks += value
            }
            if (chunks.size < NearbyProtocol.chunksFor(length)) {
                if (!read(gatt, NearbyProtocol.DATA_UUIDS[chunks.size])) complete(null)
            } else {
                complete(NearbyProtocol.decode(chunks, length))
            }
        }

        private fun read(gatt: BluetoothGatt, uuid: java.util.UUID): Boolean {
            val characteristic = gatt.getService(NearbyProtocol.SERVICE_UUID)?.getCharacteristic(uuid) ?: return false
            return gatt.readCharacteristic(characteristic)
        }
    }

    private companion object {
        const val TAG = "NearbyScanner"
        const val MTU = 517
        const val CANDIDATE_WINDOW_MS = 400L
        const val READ_TIMEOUT_MS = 8_000L
        const val RETRY_AFTER_MS = 10_000L
    }
}
