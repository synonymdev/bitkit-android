package to.bitkit.appwidget.ui.facts

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import to.bitkit.appwidget.AppWidgetEntryPoint
import to.bitkit.appwidget.AppWidgetRefreshWorker
import to.bitkit.appwidget.model.AppWidgetType

class FactsGlanceReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FactsGlanceWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        AppWidgetRefreshWorker.enqueue(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        if (appWidgetIds.isEmpty()) return

        val pendingResult = goAsync()
        val accessor = EntryPointAccessors.fromApplication(context, AppWidgetEntryPoint::class.java)
        val store = accessor.appWidgetPreferencesStore()
        val repo = accessor.appWidgetDataRepository()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                appWidgetIds.forEach { id -> store.registerWidget(id, AppWidgetType.FACTS) }
                repo.fetchFacts().onSuccess { store.cacheFacts(it) }
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val pendingResult = goAsync()
        val store = EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
            .appWidgetPreferencesStore()
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
