package to.bitkit.appwidget.ui.price

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import to.bitkit.appwidget.AppWidgetRefreshWorker

class PriceGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PriceGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AppWidgetRefreshWorker.enqueue(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        AppWidgetRefreshWorker.cancelIfNoWidgets(context)
    }
}
