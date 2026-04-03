package to.bitkit.appwidget.ui.headlines

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import kotlinx.coroutines.flow.first
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType

class HeadlinesGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = AppWidgetPreferencesStore.getInstance(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val data = store.data.first()
        val entry = data.entries.find { it.appWidgetId == appWidgetId }
            ?: AppWidgetEntry(appWidgetId = appWidgetId, type = AppWidgetType.HEADLINES)

        provideContent {
            HeadlinesGlanceContent(
                context = context,
                articles = data.cachedArticles,
                entry = entry,
            )
        }
    }
}
