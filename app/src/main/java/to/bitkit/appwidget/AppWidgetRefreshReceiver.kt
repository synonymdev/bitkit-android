package to.bitkit.appwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import to.bitkit.utils.Logger
import to.bitkit.utils.logBatterySettings

class AppWidgetRefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> scheduleAfterSystemEvent(
                context,
                AppWidgetRefreshReason.BOOT_COMPLETED,
            )

            Intent.ACTION_MY_PACKAGE_REPLACED -> scheduleAfterSystemEvent(
                context,
                AppWidgetRefreshReason.PACKAGE_REPLACED,
            )

            AppWidgetRefreshScheduler.CATCH_UP_ALARM_ACTION -> {
                Logger.debug(
                    "Received widget refresh alarm (${context.logBatterySettings()})",
                    context = TAG,
                )
                context.appWidgetRefreshScheduler.handleCatchUpAlarm(AppWidgetRefreshReason.CATCH_UP_ALARM)
            }
        }
    }

    private fun scheduleAfterSystemEvent(context: Context, reason: AppWidgetRefreshReason) {
        Logger.debug(
            "Received widget refresh event for '${reason.name}' (${context.logBatterySettings()})",
            context = TAG,
        )
        context.appWidgetRefreshScheduler.ensureScheduled(reason)
        context.appWidgetRefreshScheduler.requestCatchUp(reason)
    }

    private companion object {
        const val TAG = "AppWidgetRefreshReceiver"
    }
}
