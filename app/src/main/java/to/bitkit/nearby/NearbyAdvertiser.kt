package to.bitkit.nearby

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import to.bitkit.ext.bluetoothManager
import to.bitkit.utils.Logger

/**
 * Serves one payment request to nearby Bitkit wallets: a GATT server with the nearby service and a
 * connectable advertisement carrying only the service UUID. Advertising starts once Android confirms the
 * service is registered, so a payer never finds a phone it cannot read. [start] and [stop] run on the main thread.
 */
class NearbyAdvertiser(
    private val context: Context,
    private val onFailed: () -> Unit = {},
) {
    private val bluetoothManager = context.bluetoothManager

    @Volatile
    private var request: ByteArray? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private val isAdvertising get() = advertiseCallback != null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Counts opened servers, so a registration result for a closed server is ignored. */
    private var serverGeneration = 0
    private var isServiceRegistered = false

    /** A new callback per start, so a failure reported after [stop] or a later start is ignored. */
    private fun newAdvertiseCallback(): AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Logger.warn("Failed nearby advertising with error '$errorCode'", context = TAG)
            if (advertiseCallback !== this) return
            advertiseCallback = null
            onFailed()
        }
    }

    private fun newServerCallback(generation: Int) = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            mainHandler.post { onServiceRegistered(generation, status == BluetoothGatt.GATT_SUCCESS) }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val server = gattServer ?: return
            val value = valueFor(characteristic)
            val response = value?.let { NearbyProtocol.readAt(it, offset) }
            if (response == null) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
            } else {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, response)
            }
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun start(uri: String) {
        val encoded = NearbyProtocol.encodeRequest(uri) ?: return stop()
        request = encoded
        if (gattServer == null) gattServer = openServer() ?: return onFailed()
        if (isServiceRegistered && !isAdvertising) startAdvertising()
    }

    // start() holds both permissions, and this runs only for the server it opened.
    @SuppressLint("MissingPermission")
    private fun onServiceRegistered(generation: Int, isRegistered: Boolean) {
        if (generation != serverGeneration || gattServer == null) return
        if (!isRegistered) {
            Logger.warn("Failed to register the nearby GATT service", context = TAG)
            stop()
            onFailed()
            return
        }
        isServiceRegistered = true
        if (request != null && !isAdvertising) startAdvertising()
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun stop() {
        request = null
        advertiseCallback?.let { callback ->
            runCatching { bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(callback) }
        }
        advertiseCallback = null
        runCatching { gattServer?.close() }
        gattServer = null
        isServiceRegistered = false
        serverGeneration++
    }

    private fun valueFor(characteristic: BluetoothGattCharacteristic): ByteArray? {
        val current = request ?: return null
        if (characteristic.uuid == NearbyProtocol.META_UUID) return NearbyProtocol.meta(current)
        val index = NearbyProtocol.DATA_UUIDS.indexOf(characteristic.uuid)
        return if (index >= 0) NearbyProtocol.chunk(current, index) else null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun openServer(): BluetoothGattServer? {
        val generation = ++serverGeneration
        val server = runCatching { bluetoothManager.openGattServer(context, newServerCallback(generation)) }
            .onFailure { Logger.warn("Failed to open nearby GATT server", it, context = TAG) }
            .getOrNull() ?: return null
        val service = BluetoothGattService(NearbyProtocol.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        (listOf(NearbyProtocol.META_UUID) + NearbyProtocol.DATA_UUIDS).forEach {
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    it,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
            )
        }
        if (!runCatching { server.addService(service) }.getOrDefault(false)) {
            Logger.warn("Failed to add the nearby GATT service", context = TAG)
            runCatching { server.close() }
            return null
        }
        return server
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        val advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser ?: return onFailed()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(NearbyProtocol.SERVICE_UUID))
            .build()
        val callback = newAdvertiseCallback()
        advertiseCallback = callback
        runCatching { advertiser.startAdvertising(settings, data, callback) }
            .onFailure {
                advertiseCallback = null
                Logger.warn("Failed to start nearby advertising", it, context = TAG)
                onFailed()
            }
    }

    private companion object {
        const val TAG = "NearbyAdvertiser"
    }
}
