package to.bitkit.appwidget.ui.facts

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import to.bitkit.appwidget.AppWidgetEntryPoint
import to.bitkit.appwidget.AppWidgetRefreshWorker
import to.bitkit.appwidget.model.AppWidgetData
import to.bitkit.appwidget.model.AppWidgetType

class FactsGlanceWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val accessor = EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
        val store = accessor.appWidgetPreferencesStore()
        val repo = accessor.appWidgetDataRepository()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)

        val current = store.data.first()
        val isRegistered = current.entries.any { it.appWidgetId == appWidgetId }
        if (!isRegistered) {
            store.registerWidget(appWidgetId, AppWidgetType.FACTS)
            if (current.cachedFacts.isEmpty()) {
                repo.fetchFacts().onSuccess { store.cacheFacts(it) }
            }
            AppWidgetRefreshWorker.enqueue(context)
        }

        provideContent {
            val data by store.data.collectAsState(initial = AppWidgetData())
            val facts = data.cachedFacts
            val fact = if (facts.isEmpty()) {
                null
            } else {
                facts[data.factsRotationTick.mod(facts.size)]
            }

            FactsGlanceContent(fact = fact)
        }
    }

    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        EntryPointAccessors
            .fromApplication(context, AppWidgetEntryPoint::class.java)
            .appWidgetPreferencesStore()
            .unregisterWidget(appWidgetId)
    }
}
