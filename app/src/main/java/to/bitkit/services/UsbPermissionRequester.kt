package to.bitkit.services

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import to.bitkit.utils.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Asks the user for USB access to one device and blocks until they answer. Built on a
 * BroadcastReceiver plus a latch because the transports call it from a Rust FFI thread, never
 * from the main thread. Each transport passes its own [action] so one vendor's grant can never be
 * consumed by another vendor's receiver.
 */
class UsbPermissionRequester(
    private val context: Context,
    private val usbManager: UsbManager,
    private val action: String,
    private val timeoutMs: Long,
) {
    companion object {
        private const val TAG = "UsbPermissionRequester"
    }

    @Suppress("TooGenericExceptionCaught")
    fun request(device: UsbDevice): Boolean {
        val latch = CountDownLatch(1)
        var granted = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == action) {
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    latch.countDown()
                }
            }
        }

        val permissionIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).apply { setPackage(context.packageName) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        try {
            Logger.info("Requesting USB permission for '${device.deviceName}'", context = TAG)
            usbManager.requestPermission(device, permissionIntent)

            val responded = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!responded) {
                Logger.warn("USB permission request timed out", context = TAG)
                return false
            }

            val status = if (granted) "granted" else "denied"
            Logger.info("USB permission '$status' for '${device.deviceName}'", context = TAG)
            return granted
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }
}
