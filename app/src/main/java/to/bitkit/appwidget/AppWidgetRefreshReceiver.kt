package to.bitkit.appwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import to.bitkit.ext.powerManager
import to.bitkit.utils.Logger

class AppWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT -> enqueueCatchUp(context, "user_present")
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                if (!context.powerManager.isDeviceIdleMode) enqueueCatchUp(context, "device_idle_exit")
            }
        }
    }

    private fun enqueueCatchUp(context: Context, reason: String) {
        Logger.debug("Enqueued widget refresh for '$reason'", context = TAG)
        AppWidgetRefreshWorker.enqueueCatchUp(context)
    }

    private companion object {
        const val TAG = "AppWidgetRefreshReceiver"
    }
}
