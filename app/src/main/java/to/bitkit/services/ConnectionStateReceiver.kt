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
) : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> onBluetoothOff()
                    BluetoothAdapter.STATE_ON -> onBluetoothOn()
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                device?.deviceName?.let(onUsbDetached)
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                device?.let(onUsbAttached)
            }
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
