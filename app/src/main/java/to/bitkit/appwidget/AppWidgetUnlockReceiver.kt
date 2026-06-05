package to.bitkit.appwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import to.bitkit.ext.keyguardManager
import to.bitkit.utils.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

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
            when (intent.action) {
                Intent.ACTION_USER_PRESENT -> requestCatchUp(context, "user_present")
                Intent.ACTION_SCREEN_ON -> if (isUnlocked(context)) requestCatchUp(context, "screen_on")
            }
        }
    }

    private var registered = false
    private var lastCatchUpElapsedMs = 0L

    override fun register() {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
    }

    override fun unregister() {
        if (!registered) return
        runCatching { context.unregisterReceiver(receiver) }
        registered = false
    }

    private fun requestCatchUp(context: Context, source: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCatchUpElapsedMs < CATCH_UP_DEBOUNCE.inWholeMilliseconds) return
        lastCatchUpElapsedMs = now

        Logger.debug("Received unlock event from '$source', requesting widget catch-up", context = TAG)
        context.appWidgetRefreshScheduler.ensureScheduled(AppWidgetRefreshReason.USER_PRESENT)
        context.appWidgetRefreshScheduler.requestCatchUp(AppWidgetRefreshReason.USER_PRESENT)
    }

    private fun isUnlocked(context: Context): Boolean {
        val keyguardManager = context.keyguardManager ?: return false
        return !keyguardManager.isKeyguardLocked
    }

    private companion object {
        private const val TAG = "AppWidgetUnlockReceiver"
        private val CATCH_UP_DEBOUNCE = 30.seconds
    }
}
