package to.bitkit.appwidget.ui.blocks

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import to.bitkit.appwidget.AppWidgetRefreshWorker

class BlocksGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BlocksGlanceWidget()

    override fun onEnabled(context: android.content.Context) {
        super.onEnabled(context)
        AppWidgetRefreshWorker.enqueue(context)
    }

    override fun onDisabled(context: android.content.Context) {
        super.onDisabled(context)
        AppWidgetRefreshWorker.cancelIfNoWidgets(context)
    }
}
