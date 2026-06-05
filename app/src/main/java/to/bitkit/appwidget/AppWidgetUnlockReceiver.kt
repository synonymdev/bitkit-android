package to.bitkit.appwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton

interface AppWidgetUnlockRegistrar {
    fun register()
    fun unregister()
}

@Singleton
class AppWidgetUnlockReceiver @Inject constructor(
    @ApplicationContext private val context: Context,
) : AppWidgetUnlockRegistrar {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT) return
            Logger.debug("Received unlock event, requesting widget catch-up", context = TAG)
            context.appWidgetRefreshScheduler.ensureScheduled(AppWidgetRefreshReason.USER_PRESENT)
            context.appWidgetRefreshScheduler.requestCatchUp(AppWidgetRefreshReason.USER_PRESENT)
        }
    }

    private var registered = false

    override fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    override fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private companion object {
        private const val TAG = "AppWidgetUnlockReceiver"
    }
}
