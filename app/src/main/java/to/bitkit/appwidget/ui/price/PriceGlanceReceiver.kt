package to.bitkit.appwidget.ui.price

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import to.bitkit.appwidget.AppWidgetEntryPoint
import to.bitkit.appwidget.AppWidgetRefreshWorker

class PriceGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PriceGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AppWidgetRefreshWorker.enqueue(context)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = EntryPointAccessors
                    .fromApplication(context, AppWidgetEntryPoint::class.java)
                    .appWidgetPreferencesStore()
                appWidgetIds.forEach { store.unregisterWidget(it) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        AppWidgetRefreshWorker.cancelIfNoWidgets(context)
    }
}
