package to.bitkit.appwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetReceiver

abstract class RefreshingGlanceReceiver(
    private val enabledReason: AppWidgetRefreshReason,
    private val updateReason: AppWidgetRefreshReason,
    private val disabledReason: AppWidgetRefreshReason,
) : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        context.appWidgetRefreshScheduler.ensureScheduled(enabledReason)
        context.appWidgetRefreshScheduler.requestCatchUp(enabledReason)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        context.appWidgetRefreshScheduler.ensureScheduled(updateReason)
        context.appWidgetRefreshScheduler.requestCatchUp(updateReason)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.appWidgetRefreshScheduler.cancelIfNoWidgets(disabledReason)
    }
}
