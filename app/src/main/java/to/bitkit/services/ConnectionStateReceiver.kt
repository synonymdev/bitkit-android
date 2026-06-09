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
 * Surfaces device link loss the per-connection callbacks miss: the phone's
 * Bluetooth being switched off, or a USB device being unplugged. Transport-agnostic
 * so any hardware-wallet transport (Trezor today, other vendors later) can plug its
 * own handling into the same system events.
 */
class ConnectionStateReceiver(
    private val onBluetoothOff: () -> Unit,
    private val onUsbDetached: (path: String) -> Unit,
) : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                    onBluetoothOff()
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                device?.deviceName?.let(onUsbDetached)
            }
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(context, this, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}
