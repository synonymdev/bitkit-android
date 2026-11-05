package to.bitkit.ext

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresPermission
import java.util.concurrent.Executor

/** Delivers GATT callbacks on the Binder thread that reports them, as the handler-less `connectGatt` overloads do. */
private val directExecutor = Executor(Runnable::run)

/**
 * Opens a direct GATT client connection to this device.
 *
 * From API 37 this uses [BluetoothGattConnectionSettings], with automatic MTU exchange turned off because the
 * hardware wallet transports request their own MTU once connected. Older releases have only the legacy
 * overload, which already behaves that way.
 */
@RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
fun BluetoothDevice.connectGattDirect(
    context: Context,
    callback: BluetoothGattCallback,
    transport: Int,
): BluetoothGatt? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
        val settings = BluetoothGattConnectionSettings.Builder()
            .setAutoConnectEnabled(false)
            .setAutomaticMtuEnabled(false)
            .setTransport(transport)
            .build()
        return connectGatt(settings, directExecutor, callback)
    }
    @Suppress("DEPRECATION") // The replacement above does not exist before API 37.
    return connectGatt(context, false, callback, transport)
}
