package to.bitkit.services

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/**
 * Surfaces device link changes the per-connection callbacks miss: the phone's
 * Bluetooth being switched off or back on, and a USB device being unplugged or
 * plugged in. Transport-agnostic so any hardware-wallet transport (Trezor today,
 * other vendors later) can plug its own handling into the same system events.
 */
class ConnectionStateReceiver(
    private val onBluetoothOff: () -> Unit,
    private val onBluetoothOn: () -> Unit,
    private val onUsbDetached: (path: String) -> Unit,
    private val onUsbAttached: (device: UsbDevice) -> Unit,
) {
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return

            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> onBluetoothOff()
                BluetoothAdapter.STATE_ON -> onBluetoothOn()
            }
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> device?.deviceName?.let(onUsbDetached)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.let(onUsbAttached)
                }
            }
        }
    }

    fun register(context: Context) {
        ContextCompat.registerReceiver(
            context,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_EXPORTED,
        )

        val usbFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            usbFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }
}
