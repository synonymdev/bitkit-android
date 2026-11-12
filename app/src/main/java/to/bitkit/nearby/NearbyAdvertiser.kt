package to.bitkit.nearby

import android.Manifest
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
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import to.bitkit.ext.bluetoothManager
import to.bitkit.utils.Logger

/**
 * Serves one payment request to nearby Bitkit wallets: a GATT server with the nearby service and a
 * connectable advertisement carrying only the service UUID. [start] and [stop] run on the main thread.
 */
class NearbyAdvertiser(private val context: Context) {
    private val bluetoothManager = context.bluetoothManager

    @Volatile
    private var request: ByteArray? = null
    private var gattServer: BluetoothGattServer? = null
    private var isAdvertising = false

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            Logger.warn("Failed nearby advertising with error '$errorCode'", context = TAG)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
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
        if (gattServer == null) gattServer = openServer() ?: return
        if (!isAdvertising) startAdvertising()
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE])
    fun stop() {
        request = null
        if (isAdvertising) {
            runCatching { bluetoothManager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
            isAdvertising = false
        }
        runCatching { gattServer?.close() }
        gattServer = null
    }

    private fun valueFor(characteristic: BluetoothGattCharacteristic): ByteArray? {
        val current = request ?: return null
        if (characteristic.uuid == NearbyProtocol.META_UUID) return NearbyProtocol.meta(current)
        val index = NearbyProtocol.DATA_UUIDS.indexOf(characteristic.uuid)
        return if (index >= 0) NearbyProtocol.chunk(current, index) else null
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun openServer(): BluetoothGattServer? {
        val server = runCatching { bluetoothManager.openGattServer(context, serverCallback) }
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
        server.addService(service)
        return server
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startAdvertising() {
        val advertiser = bluetoothManager.adapter?.bluetoothLeAdvertiser ?: return
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
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
            .onSuccess { isAdvertising = true }
            .onFailure { Logger.warn("Failed to start nearby advertising", it, context = TAG) }
    }

    private companion object {
        const val TAG = "NearbyAdvertiser"
    }
}
