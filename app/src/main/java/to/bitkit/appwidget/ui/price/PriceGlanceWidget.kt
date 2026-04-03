package to.bitkit.appwidget.ui.price

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.provideContent
import kotlinx.coroutines.flow.first
import to.bitkit.appwidget.AppWidgetPreferencesStore
import to.bitkit.appwidget.model.AppWidgetEntry
import to.bitkit.appwidget.model.AppWidgetType

class PriceGlanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = AppWidgetPreferencesStore.getInstance(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val data = store.data.first()
        val entry = data.entries.find { it.appWidgetId == appWidgetId }
            ?: AppWidgetEntry(appWidgetId = appWidgetId, type = AppWidgetType.PRICE)

        provideContent {
            PriceGlanceContent(
                context = context,
                price = data.cachedPrice,
                entry = entry,
            )
        }
    }
}
