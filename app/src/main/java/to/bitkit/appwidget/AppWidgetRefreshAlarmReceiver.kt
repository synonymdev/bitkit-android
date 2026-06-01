package to.bitkit.appwidget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import to.bitkit.utils.Logger

class AppWidgetRefreshAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetRefreshWorker.CATCH_UP_ALARM_ACTION) return

        Logger.debug("Received widget refresh alarm", context = TAG)
        AppWidgetRefreshWorker.enqueueCatchUp(context)
        AppWidgetRefreshWorker.scheduleCatchUpAlarm(context)
    }

    private companion object {
        const val TAG = "AppWidgetRefreshAlarmReceiver"
    }
}
